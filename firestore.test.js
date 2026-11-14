const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

const safeTimestamp = () => new Date(Date.now() - 1000);

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
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

test("Unauthenticated user cannot read or list studySessions", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("studySessions").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("studySessions")
      .doc("sess_1")
      .get()
  );
});

test("Authenticated owner can create, list, update bookmark, and delete studySession", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("studySessions")
    .doc("sess_1");

  await assertSucceeds(
    docRef.set({
      userId: ALICE_UID,
      title: "Newton's Second Law",
      category: "question",
      prompt: "Explain F = ma with an example.",
      answer: "Force equals mass times acceleration...",
      attachmentName: "",
      attachmentMimeType: "",
      modelUsed: "gemini-4-hydrogen",
      isDeepThinking: false,
      isBookmarked: false,
      createdAt: safeTimestamp(),
      updatedAt: safeTimestamp(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("studySessions").get()
  );

  await assertSucceeds(
    docRef.update({
      isBookmarked: true,
      updatedAt: safeTimestamp(),
    })
  );

  await assertSucceeds(docRef.delete());
});

test("Cross-user access to another user's studySessions is denied", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx
      .firestore()
      .collection("users")
      .doc(BOB_UID)
      .collection("studySessions")
      .doc("bob_sess")
      .set({
        userId: BOB_UID,
        title: "Bob Paper",
        category: "paper",
        prompt: "Solve this paper",
        answer: "Solution...",
        modelUsed: "gemini-4-carbon",
        isDeepThinking: true,
        isBookmarked: false,
        createdAt: safeTimestamp(),
        updatedAt: safeTimestamp(),
      });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("studySessions").get()
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("studySessions")
      .doc("bob_sess")
      .get()
  );
});

test("Shadow field injection or invalid category fails on create", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("studySessions")
    .doc("sess_bad");

  await assertFails(
    docRef.set({
      userId: ALICE_UID,
      title: "Test",
      category: "question",
      prompt: "Hi",
      answer: "Hello",
      modelUsed: "gemini-4-hydrogen",
      isDeepThinking: false,
      isBookmarked: false,
      isAdmin: true,
      createdAt: safeTimestamp(),
      updatedAt: safeTimestamp(),
    })
  );

  await assertFails(
    docRef.set({
      userId: ALICE_UID,
      title: "Test",
      category: "invalid_cat",
      prompt: "Hi",
      answer: "Hello",
      modelUsed: "gemini-4-hydrogen",
      isDeepThinking: false,
      isBookmarked: false,
      createdAt: safeTimestamp(),
      updatedAt: safeTimestamp(),
    })
  );
});

test("Authenticated owner can create and list chatMessages while Bob is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const msgRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("chatMessages")
    .doc("msg_1");

  await assertSucceeds(
    msgRef.set({
      userId: ALICE_UID,
      role: "user",
      text: "What is photosynthesis?",
      modelUsed: "gemini-4-hydrogen",
      isDeepThinking: false,
      createdAt: safeTimestamp(),
    })
  );

  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("chatMessages").get()
  );
});
