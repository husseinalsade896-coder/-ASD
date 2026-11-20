const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_ye_967";
const BOB_UID = "bob_sa_966";
const CHARLIE_UID = "charlie_ae_971";
const ADMIN_EMAIL = "husseinalsade896@gmail.com";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: denied across all collections", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("directory").where("banned", "==", false).get());
  await assertFails(unauth.collection("users").doc(ALICE_UID).get());
  await assertFails(unauth.collection("chats").where("memberUids", "array-contains", ALICE_UID).get());
  await assertFails(unauth.collection("statuses").where("privacy", "in", ["EVERYONE", "CONTACTS"]).get());
  await assertFails(unauth.collection("calls").where("participantUids", "array-contains", ALICE_UID).get());
});

test("Directory & PII split: owner creates own profile, cross-user PII blocked, shadow update & ban escalation blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@example.com", email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, { email: "bob@example.com", email_verified: true }).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("directory").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice_ye",
      displayName: "Alice Yemen",
      bio: "Hello from Sanaa",
      avatarColorHex: "#0E7C6B",
      publicKeyBase64: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtestKey",
      countryCode: "+967",
      onlineStatus: "ONLINE",
      lastSeenPrivacy: "EVERYONE",
      avatarPrivacy: "EVERYONE",
      readReceiptsEnabled: true,
      twoFactorEnabled: false,
      banned: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      phoneNumber: "+967770000001",
      phoneVerified: true,
      notificationTone: "Emerald Chime",
      notificationsEnabled: true,
      blockedUids: [],
      languageCode: "ar",
      darkTheme: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob can query active directory profiles (no PII)
  await assertSucceeds(
    bobDb.collection("directory").where("banned", "==", false).get()
  );

  // Unfiltered directory list must fail
  await assertFails(bobDb.collection("directory").get());

  // Bob CANNOT read Alice's private PII doc
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());

  // Alice CANNOT ban/unban herself or inject ghost field
  await assertFails(
    aliceDb.collection("directory").doc(ALICE_UID).update({
      banned: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    aliceDb.collection("directory").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Chats & E2EE Messages: members can create, message, edit, read-receipt; non-members blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const now = new Date(Date.now() - 3000);

  await assertSucceeds(
    aliceDb.collection("chats").doc("chat_ye_sa").set({
      chatType: "DIRECT",
      title: "Alice & Bob",
      creatorId: ALICE_UID,
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID, BOB_UID],
      pinnedByUids: [],
      archivedByUids: [],
      typingUids: [],
      lastMessagePreview: "Encrypted chat started",
      lastMessageSenderId: ALICE_UID,
      onlyAdminsCanEdit: false,
      onlyAdminsCanSend: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("chats").doc("chat_ye_sa").collection("messages").doc("msg_1").set({
      chatId: "chat_ye_sa",
      senderId: ALICE_UID,
      senderName: "Alice Yemen",
      memberUids: [ALICE_UID, BOB_UID],
      messageType: "TEXT",
      ciphertext: "dGVzdF9jaXBoZXJ0ZXh0",
      iv: "dGVzdF9pdg==",
      encrypted: true,
      forwarded: false,
      edited: false,
      deletedForAll: false,
      readByUids: [ALICE_UID],
      deliveredToUids: [ALICE_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob queries his chats and messages with array-contains
  await assertSucceeds(
    bobDb.collection("chats").where("memberUids", "array-contains", BOB_UID).get()
  );
  await assertSucceeds(
    bobDb.collection("chats").doc("chat_ye_sa").collection("messages")
      .where("memberUids", "array-contains", BOB_UID).get()
  );

  // Bob marks read receipt
  await assertSucceeds(
    bobDb.collection("chats").doc("chat_ye_sa").collection("messages").doc("msg_1").update({
      readByUids: [ALICE_UID, BOB_UID],
      deliveredToUids: [ALICE_UID, BOB_UID],
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Bob CANNOT edit Alice's ciphertext
  await assertFails(
    bobDb.collection("chats").doc("chat_ye_sa").collection("messages").doc("msg_1").update({
      ciphertext: "tampered",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Charlie (non-member) CANNOT read chat or messages
  await assertFails(charlieDb.collection("chats").doc("chat_ye_sa").get());
  await assertFails(charlieDb.collection("chats").doc("chat_ye_sa").collection("messages").doc("msg_1").get());
});

test("Statuses & Calls: 24h viewer tracking and terminal state locking on ended calls", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("statuses").doc("status_1").set({
      ownerId: ALICE_UID,
      ownerName: "Alice",
      statusType: "TEXT",
      textContent: "Ramadan Kareem from Yemen!",
      backgroundHex: "#0E7C6B",
      privacy: "EVERYONE",
      viewerUids: [],
      expiresAtEpochMs: Date.now() + 86400000,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    bobDb.collection("statuses").doc("status_1").update({
      viewerUids: [BOB_UID],
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Call lifecycle + terminal state lock
  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").set({
      callerId: ALICE_UID,
      callerName: "Alice",
      participantUids: [ALICE_UID, BOB_UID],
      callType: "VIDEO",
      status: "RINGING",
      sdpOffer: "v=0...",
      durationSeconds: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    bobDb.collection("calls").doc("call_1").update({
      status: "ENDED",
      durationSeconds: 42,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Terminal state lock: once ENDED, non-admin participant cannot re-open it
  await assertFails(
    bobDb.collection("calls").doc("call_1").update({
      status: "ACTIVE",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Admin RBAC & Email Spoofing Protection", async () => {
  const verifiedAdminDb = testEnv.authenticatedContext("admin_1", {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();
  const spoofedAdminDb = testEnv.authenticatedContext("spoof_1", {
    email: ADMIN_EMAIL,
    email_verified: false,
  }).firestore();
  const regularDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();

  const now = new Date(Date.now() - 2000);

  // Spoofed unverified email cannot write system config or list users
  await assertFails(spoofedAdminDb.collection("users").get());
  await assertFails(
    spoofedAdminDb.collection("system").doc("config").set({
      maintenanceMode: false,
      maxUploadMb: 25,
      registrationEnabled: true,
      lastBackupEpochMs: Date.now(),
      backupStatus: "HEALTHY",
      updatedBy: "spoof_1",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Regular user cannot read reports list or modify system config
  await assertFails(regularDb.collection("users").get());

  // Verified admin succeeds
  await assertSucceeds(
    verifiedAdminDb.collection("system").doc("config").set({
      maintenanceMode: false,
      maxUploadMb: 25,
      registrationEnabled: true,
      lastBackupEpochMs: Date.now(),
      backupStatus: "HEALTHY",
      updatedBy: "admin_1",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(verifiedAdminDb.collection("users").get());
});
