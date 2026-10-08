// Usage: node mint.js <uid> <firebaseApiKey> <serviceAccountPath> <outEnvFile>
// Mints a Firebase custom token for <uid> via the Admin SDK, exchanges it for a
// real ID token via identitytoolkit, and appends/updates K6_ID_TOKEN in
// <outEnvFile>. Never prints the token to stdout.
const { initializeApp, cert } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const fs = require('fs');

const [, , uid, apiKey, saPath, outEnvFile] = process.argv;
if (!uid || !apiKey || !saPath || !outEnvFile) {
  console.error('Usage: node mint.js <uid> <firebaseApiKey> <serviceAccountPath> <outEnvFile>');
  process.exit(1);
}

const serviceAccount = JSON.parse(fs.readFileSync(saPath, 'utf8'));

initializeApp({ credential: cert(serviceAccount) });

async function main() {
  const customToken = await getAuth().createCustomToken(uid);

  const res = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=${apiKey}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: customToken, returnSecureToken: true }),
    },
  );
  const body = await res.json();
  if (!res.ok) {
    console.error('ERREUR échange token:', res.status, JSON.stringify(body));
    process.exit(2);
  }

  const idToken = body.idToken;

  let env = fs.existsSync(outEnvFile) ? fs.readFileSync(outEnvFile, 'utf8') : '';
  if (/^K6_ID_TOKEN=.*$/m.test(env)) {
    env = env.replace(/^K6_ID_TOKEN=.*$/m, `K6_ID_TOKEN=${idToken}`);
  } else {
    env += `\nK6_ID_TOKEN=${idToken}\n`;
  }
  fs.writeFileSync(outEnvFile, env);

  console.log(`OK — K6_ID_TOKEN écrit dans ${outEnvFile} (longueur ${idToken.length} caractères, expire dans ~1h)`);
}

main().catch((e) => {
  console.error('ERREUR:', e.message);
  process.exit(3);
});
