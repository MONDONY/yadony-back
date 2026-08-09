# language: fr
@smoke @auth
Fonctionnalité: Inscription des utilisateurs
  En tant que nouvel utilisateur
  Je veux m'inscrire sur la plateforme Yadony
  Afin de pouvoir envoyer ou transporter des colis

  @happy-path @critical
  Scénario: Inscription réussie en tant qu'expéditeur
    Etant donné un token Firebase pour l'uid "new-sender-001"
    Quand je m'inscris avec le téléphone "+33611000001" et le rôle "SENDER"
    Alors la réponse HTTP est 201
    Et la réponse contient un identifiant utilisateur
    Et la réponse contient le rôle "SENDER"

  @happy-path @critical
  Scénario: Inscription — le rôle voyageur est accordé d'office
    Etant donné un token Firebase pour l'uid "new-traveler-001"
    Quand je m'inscris avec le téléphone "+33611000002" et le rôle "TRAVELER"
    Alors la réponse HTTP est 201
    Et la réponse contient un identifiant utilisateur
    Et la réponse contient le rôle "SENDER"
    Et la réponse contient le rôle "TRAVELER"

  @happy-path
  Scénario: Inscription expéditeur — cumul des deux rôles dès l'inscription
    Etant donné un token Firebase pour l'uid "new-both-001"
    Quand je m'inscris avec le téléphone "+33611000003" et le rôle "SENDER"
    Alors la réponse HTTP est 201
    Et la réponse contient le rôle "SENDER"
    Et la réponse contient le rôle "TRAVELER"

  @happy-path
  Scénario: Ré-inscription d'un utilisateur existant — idempotent
    Etant donné un token Firebase pour l'uid "existing-user-001"
    Et je m'inscris avec le téléphone "+33611000004" et le rôle "SENDER"
    Quand je m'inscris avec le téléphone "+33611000004" et le rôle "SENDER"
    Alors la réponse HTTP est 201

  @error-case
  Scénario: Le rôle demandé à l'inscription est ignoré — aucune auto-attribution privilégiée
    Etant donné un token Firebase pour l'uid "would-be-admin-001"
    Quand je m'inscris avec le téléphone "+33611000005" et le rôle "ADMIN"
    Alors la réponse HTTP est 201
    Et la réponse contient le rôle "SENDER"

  Scénario: Un même numéro ne donne qu'un seul compte — l'unicité est garantie par Firebase
    # Le numéro n'est plus stocké en base : un numéro correspond à un unique compte
    # Firebase, donc au même uid, donc au même compte yadony. Une seconde inscription
    # avec ce numéro retombe sur le compte existant au lieu d'en créer un second.
    Etant donné un token Firebase pour l'uid "user-phone-a"
    Et je m'inscris avec le téléphone "+33611000006" et le rôle "SENDER"
    Quand je m'inscris avec le téléphone "+33611000006" et le rôle "TRAVELER"
    Alors la réponse HTTP est 201
    Et la réponse contient un identifiant utilisateur

  @error-case
  Scénario: Inscription sans token Firebase — non autorisé
    Etant donné aucun utilisateur n'est authentifié
    Quand je m'inscris avec le téléphone "+33611000007" et le rôle "SENDER"
    Alors la réponse HTTP est 401
