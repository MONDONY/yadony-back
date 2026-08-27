# language: fr
@regression @auth
Fonctionnalité: Gestion avancée du compte
  En tant qu'utilisateur inscrit
  Je veux gérer ma confidentialité, mon consentement, mon statut PRO et mes blocages
  Afin de contrôler mon expérience sur la plateforme

  @happy-path
  Scénario: Confidentialité, consentement analytique et token FCM
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "acct-001" et le téléphone "+33655000001"
    Etant donné l'utilisateur "acct-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je consulte mes paramètres de confidentialité
    Alors la réponse HTTP est 200
    Quand je mets à jour mes paramètres de confidentialité
    Alors la réponse HTTP est 204
    Quand je consulte mon consentement analytique
    Alors la réponse HTTP est 200
    Quand je donne mon consentement analytique
    Alors la réponse HTTP est 204
    Quand je mets à jour mon token FCM
    Alors la réponse HTTP est 204

  @happy-path
  Scénario: Complément du profil avec email, ville et date de naissance
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "acct-profil-001" et le téléphone "+33655000010"
    Etant donné l'utilisateur "acct-profil-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je complète mon profil avec l'email "mamadou@example.com", la ville "Lyon" et la date de naissance "1990-05-15"
    Alors la réponse HTTP est 200
    Et le champ "city" de la réponse vaut "Lyon"

  @happy-path
  Scénario: Mise à jour du profil PRO (abonné) puis retour au compte standard
    # Depuis le lot Stripe billing, l'endpoint upgrade-to-pro ne fait plus que mettre à
    # jour la raison sociale/le SIRET : le statut PRO s'obtient désormais par abonnement
    # payant (Stripe Checkout). On simule ici un abonné déjà PRO pour vérifier que la
    # mise à jour de son profil (200) et le renoncement au compte PRO (200) fonctionnent
    # toujours.
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "acct-002" et le téléphone "+33655000002"
    Et le compte "acct-002" est un compte PRO
    Etant donné l'utilisateur "acct-002" est authentifié en tant qu'EXPÉDITEUR
    Quand je mets à jour mon profil pro
    Alors la réponse HTTP est 200
    Quand je repasse mon compte en standard
    Alors la réponse HTTP est 200

  @happy-path
  Scénario: Blocage puis déblocage d'un utilisateur
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "acct-blocker-001" et le téléphone "+33655000003"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "acct-blocked-001" et le téléphone "+33655000004"
    Et l'identifiant du compte "acct-blocked-001" est sauvegardé sous "blocked-acct"
    Etant donné l'utilisateur "acct-blocker-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je bloque l'utilisateur "blocked-acct"
    Alors la réponse HTTP est 204
    Quand je consulte ma liste de blocage
    Alors la réponse HTTP est 200
    Et la réponse est une liste de 1 élément
    Quand je débloque l'utilisateur "blocked-acct"
    Alors la réponse HTTP est 204
