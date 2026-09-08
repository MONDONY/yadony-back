#!/usr/bin/env python3
"""Jeton Firebase (ID token, 1 h) pour un utilisateur STAGING, à partir de son uid.

Sert à appeler l'API staging avec curl (recette du rail mobile money, par exemple)
sans passer par l'app. Aucune dépendance Python : la signature RS256 du jeton
personnalisé est déléguée à openssl, l'échange contre un ID token passe par
l'API REST Firebase.

    python3 scripts/staging-firebase-token.py <uid>
    TOKEN=$(python3 scripts/staging-firebase-token.py <uid>)
    curl -H "Authorization: Bearer $TOKEN" https://api-staging.yadony.com/api/v1/users/me

Entrées (surchargeables par variables d'environnement) :
  FIREBASE_SERVICE_ACCOUNT  compte de service du projet staging (gitignoré)
                            défaut : src/main/resources/firebase-service-account.json
  FIREBASE_WEB_API_KEY      clé API Firebase du projet staging ; défaut : lue dans
                            ../dony_app/env.staging.json (FIREBASE_ANDROID_API_KEY)

Le compte de service DOIT être celui du projet staging (yadony-f1f0f) : le script
refuse tout autre projet. Un uid inconnu de Firebase créerait un utilisateur vide,
donc vérifier l'uid dans la console Firebase (Authentication > Users) avant.
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

STAGING_PROJECT = "yadony-f1f0f"
AUDIENCE = "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def die(message: str) -> None:
    print(message, file=sys.stderr)
    sys.exit(1)


def load_service_account() -> dict:
    path = os.environ.get("FIREBASE_SERVICE_ACCOUNT",
                          os.path.join(ROOT, "src", "main", "resources", "firebase-service-account.json"))
    try:
        with open(path) as f:
            account = json.load(f)
    except FileNotFoundError:
        die("Compte de service introuvable : " + path)
    if account.get("project_id") != STAGING_PROJECT:
        die("Compte de service du projet " + str(account.get("project_id")) + ", attendu " + STAGING_PROJECT)
    return account


def load_api_key() -> str:
    key = os.environ.get("FIREBASE_WEB_API_KEY")
    if key:
        return key
    env_path = os.path.join(os.path.dirname(ROOT), "dony_app", "env.staging.json")
    try:
        with open(env_path) as f:
            return json.load(f)["FIREBASE_ANDROID_API_KEY"]
    except (FileNotFoundError, KeyError):
        die("Clé API introuvable : poser FIREBASE_WEB_API_KEY ou fournir " + env_path)


def custom_token(account: dict, uid: str) -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    payload = b64url(json.dumps({
        "iss": account["client_email"],
        "sub": account["client_email"],
        "aud": AUDIENCE,
        "iat": now,
        "exp": now + 3600,
        "uid": uid,
    }).encode())
    signing_input = (header + "." + payload).encode()
    with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as key_file:
        os.chmod(key_file.name, 0o600)
        key_file.write(account["private_key"])
        key_path = key_file.name
    try:
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_path],
            input=signing_input, capture_output=True, check=True).stdout
    finally:
        os.unlink(key_path)
    return header + "." + payload + "." + b64url(signature)


def exchange(api_key: str, token: str) -> str:
    url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=" + api_key
    body = json.dumps({"token": token, "returnSecureToken": True}).encode()
    request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)["idToken"]
    except urllib.error.HTTPError as error:
        details = json.loads(error.read().decode()).get("error", {}).get("message", "?")
        die("Échange refusé par Firebase (HTTP " + str(error.code) + ") : " + details)


def main() -> None:
    if len(sys.argv) < 2 or not sys.argv[1].strip():
        die("Usage : staging-firebase-token.py <uid> [--custom-only]")
    uid = sys.argv[1].strip()
    account = load_service_account()
    token = custom_token(account, uid)
    if "--custom-only" in sys.argv[2:]:
        print(token)
        return
    print(exchange(load_api_key(), token))


if __name__ == "__main__":
    main()
