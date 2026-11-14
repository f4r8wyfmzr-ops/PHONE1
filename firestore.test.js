const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

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

function validProfile(uid) {
  return {
    userId: uid,
    displayName: "Aarav Sharma",
    email: "aarav@example.com",
    preferredLanguage: "en",
    biometricLockEnabled: true,
    dailyLimitPaise: 10000000,
    perTxnLimitPaise: 5000000,
    createdAt: new Date(),
    updatedAt: new Date(),
  };
}

function validInitiatedTx(uid, overrides = {}) {
  return {
    userId: uid,
    pspOrderId: "ORD_PSP_001",
    txRef: "TR_NPCI_001",
    payeeVpa: "chaiwala@okaxis",
    payeeName: "Chai Point",
    merchantCode: "5814",
    amountPaise: 15000,
    currency: "INR",
    note: "Tea and snacks",
    status: "INITIATED",
    pspVerified: false,
    utrNumber: "",
    failureReason: "",
    fraudRiskScore: 5,
    fraudFlags: [],
    pspAppPackage: "",
    createdAt: new Date(),
    updatedAt: new Date(),
    ...overrides,
  };
}

test("Unauthenticated user cannot read or write profiles or transactions", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("transactions").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID)));
});

test("Owner can create and read own profile, non-owner is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
});

test("Client cannot forge VERIFIED_SUCCESS status or utrNumber in Firestore", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const txCollection = aliceDb.collection("users").doc(ALICE_UID).collection("transactions");

  await assertFails(
    txCollection.doc("tx_fake_1").set(
      validInitiatedTx(ALICE_UID, {
        status: "VERIFIED_SUCCESS",
        pspVerified: true,
        utrNumber: "431900112233",
      })
    )
  );

  await assertFails(
    txCollection.doc("tx_fake_2").set(
      validInitiatedTx(ALICE_UID, {
        pspVerified: true,
      })
    )
  );
});

test("Owner can create INITIATED transaction and update to PSP_UNAVAILABLE, then terminal state locks", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const txDoc = aliceDb.collection("users").doc(ALICE_UID).collection("transactions").doc("tx_real_1");

  await assertSucceeds(txDoc.set(validInitiatedTx(ALICE_UID)));
  await assertSucceeds(
    txDoc.update({
      status: "PSP_UNAVAILABLE",
      failureReason: "Backend PSP credentials not configured",
      updatedAt: new Date(),
    })
  );
  // Terminal state locking prevents further client mutations
  await assertFails(
    txDoc.update({
      status: "AWAITING_PSP_CONFIRMATION",
      updatedAt: new Date(),
    })
  );
});

test("Cross-user transaction read and write are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("transactions").doc("tx_alice").set(validInitiatedTx(ALICE_UID))
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("transactions").doc("tx_alice").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("transactions").get()
  );
});

test("Shadow update with ghost field is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      ...validProfile(ALICE_UID),
      isAdmin: true,
    })
  );
});
