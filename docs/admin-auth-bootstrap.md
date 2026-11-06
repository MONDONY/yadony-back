# Bootstrap du compte SUPER_ADMIN

Procédure de provisionnement du compte admin racine (`SUPER_ADMIN`) sur un
environnement neuf. `POST /api/v1/admin/bootstrap` est **creation-only** :
il ne fonctionne qu'une seule fois, tant qu'aucun `SUPER_ADMIN` n'existe.

## 1. Configurer les secrets

Dans le gestionnaire de secrets de l'environnement cible, définir :

| Variable | Rôle |
|---|---|
| `ADMIN_ROOT_EMAIL` | E-mail du compte racine (jamais écrit dans le code public). Vide = bootstrap désactivé |
| `ADMIN_BOOTSTRAP_SECRET` | Secret partagé attendu dans le header `X-Bootstrap-Secret` |
| `ADMIN_BOOTSTRAP_EMAIL` | Doit être égal à `ADMIN_ROOT_EMAIL` (casse et espaces ignorés), toute autre valeur est rejetée |
| `ADMIN_BOOTSTRAP_PASSWORD` | Mot de passe initial du compte racine, 12 caractères minimum |

Ces trois variables ne doivent **jamais** apparaître en clair dans le code,
Git, les logs, ou les tests. Tant qu'une de ces variables est absente, l'endpoint
répond `404` (indistinguable d'une route inexistante).

## 2. Déployer le backend

Déployer normalement. Au démarrage, si les trois variables sont présentes,
`POST /api/v1/admin/bootstrap` devient joignable.

## 3. Appeler le bootstrap une fois

```bash
curl -i -X POST https://<host>/api/v1/admin/bootstrap \
  -H "X-Bootstrap-Secret: <valeur de ADMIN_BOOTSTRAP_SECRET>"
```

- **`201 Created`**, corps `{"email": "<ADMIN_ROOT_EMAIL>"}` → compte créé. Le mot de passe configuré n'est jamais renvoyé dans la réponse.
- **`409 Conflict`** → un `SUPER_ADMIN` existe déjà, aucune réinitialisation n'a lieu.
- **`404 Not Found`** → une des variables (dont `ADMIN_ROOT_EMAIL`) n'est pas configurée.

## 4. Vérifier et se connecter

1. Confirmer le `201` et l'email retourné.
2. Se connecter sur `dony-admin` avec l'e-mail racine et le mot de passe configuré à l'étape 1.
3. Changer immédiatement ce mot de passe (le compte est créé avec `mustChangePassword=true`).

## 5. Refermer le bootstrap

1. **Supprimer les trois secrets** (`ADMIN_BOOTSTRAP_SECRET`, `ADMIN_BOOTSTRAP_EMAIL`, `ADMIN_BOOTSTRAP_PASSWORD`) du gestionnaire de secrets.
2. Redémarrer le backend.
3. Vérifier que l'endpoint répond maintenant `404` :

```bash
curl -i -X POST https://<host>/api/v1/admin/bootstrap -H "X-Bootstrap-Secret: anything"
# → 404
```

## Notes

- Le provisionnement du vrai compte racine est une action de déploiement explicite, jamais exécutée automatiquement depuis les tests ou un poste local.
- Toute création ultérieure de compte `ADMIN`/`SUPPORT` passe par le panel `dony-admin` (`/administrateurs`, réservé au `SUPER_ADMIN`), pas par cet endpoint.
