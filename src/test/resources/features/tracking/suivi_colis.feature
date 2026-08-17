# language: fr
@smoke @tracking
Fonctionnalité: Suivi de colis
  En tant qu'expéditeur ou voyageur
  Je veux suivre l'état du transport d'un colis
  Afin de connaître sa position et confirmer la livraison

  @happy-path
  Scénario: Recherche par numéro de suivi — offre acceptée sans paiement
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-track-001" et le téléphone "+33688000001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-track-1"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-track-001" et le téléphone "+33688000002"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-track-1"
    Et l'offre "offre-track-1" est sauvegardée
    Etant donné l'utilisateur "traveler-track-001" est authentifié en tant que VOYAGEUR
    Et le paiement de l'offre "offre-track-1" est validé
    Et j'accepte l'offre "offre-track-1"
    Quand je recherche le colis avec le numéro de suivi de l'offre "offre-track-1"
    Alors la réponse HTTP est 200
    Et la réponse contient l'étape courante "ACCEPTED"

  @error-case
  Scénario: Numéro de suivi inexistant
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-track-404" et le téléphone "+33688000099"
    Quand je recherche le colis avec le numéro "DON-XXXXXX"
    Alors la réponse HTTP est 404
    Et le code d'erreur de la réponse est "tracking-not-found"

  @happy-path @critical
  Scénario: Scan d'un événement de transit par le voyageur
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-track-002" et le téléphone "+33688000003"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-track-2"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-track-002" et le téléphone "+33688000004"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-track-2"
    Et l'offre "offre-track-2" est sauvegardée
    Etant donné l'utilisateur "traveler-track-002" est authentifié en tant que VOYAGEUR
    Et le paiement de l'offre "offre-track-2" est validé
    Et j'accepte l'offre "offre-track-2"
    Quand je scanne un événement "TRANSIT" sur l'offre "offre-track-2"
    Alors la réponse HTTP est 201
    Et la réponse contient le type d'événement "TRANSIT"

  @happy-path @critical
  Scénario: Flux complet de livraison — DEPART, code de confirmation, ARRIVEE
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-track-003" et le téléphone "+33688000005"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-track-3"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-track-003" et le téléphone "+33688000006"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-track-3"
    Et l'offre "offre-track-3" est sauvegardée
    Etant donné l'utilisateur "traveler-track-003" est authentifié en tant que VOYAGEUR
    Et le paiement de l'offre "offre-track-3" est validé
    Et j'accepte l'offre "offre-track-3"
    Et je scanne un événement "DEPART" sur l'offre "offre-track-3"
    Etant donné l'utilisateur "sender-track-003" est authentifié en tant qu'EXPÉDITEUR
    Et l'expéditeur récupère le code de confirmation de l'offre "offre-track-3"
    Etant donné l'utilisateur "traveler-track-003" est authentifié en tant que VOYAGEUR
    Quand le voyageur confirme la livraison de l'offre "offre-track-3" avec le code sauvegardé
    Alors la réponse HTTP est 200
    Et la réponse contient le type d'événement "ARRIVEE"

  @error-case
  Scénario: Tentative de scanner un ARRIVEE directement — refusée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-track-004" et le téléphone "+33688000007"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-track-4"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-track-004" et le téléphone "+33688000008"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-track-4"
    Et l'offre "offre-track-4" est sauvegardée
    Etant donné l'utilisateur "traveler-track-004" est authentifié en tant que VOYAGEUR
    Et le paiement de l'offre "offre-track-4" est validé
    Et j'accepte l'offre "offre-track-4"
    Quand je tente de scanner un événement ARRIVEE sur l'offre "offre-track-4"
    Alors la réponse HTTP est 422
    Et le code d'erreur de la réponse est "use-confirm-delivery"

  @error-case
  Scénario: Code de confirmation incorrect
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-track-005" et le téléphone "+33688000009"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-track-5"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-track-005" et le téléphone "+33688000010"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-track-5"
    Et l'offre "offre-track-5" est sauvegardée
    Etant donné l'utilisateur "traveler-track-005" est authentifié en tant que VOYAGEUR
    Et le paiement de l'offre "offre-track-5" est validé
    Et j'accepte l'offre "offre-track-5"
    Et je scanne un événement "DEPART" sur l'offre "offre-track-5"
    Quand le voyageur confirme la livraison de l'offre "offre-track-5" avec le code "000000"
    Alors la réponse HTTP est 422
    Et le code d'erreur de la réponse est "code-incorrect"

  @happy-path
  Scénario: QR code, régénération du code de confirmation et événements
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-track-007" et le téléphone "+33688000013"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-track-7"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-track-007" et le téléphone "+33688000014"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-track-7"
    Et l'offre "offre-track-7" est sauvegardée
    Et le paiement de l'offre "offre-track-7" est validé
    Etant donné l'utilisateur "traveler-track-007" est authentifié en tant que VOYAGEUR
    Et j'accepte l'offre "offre-track-7"
    Etant donné l'utilisateur "sender-track-007" est authentifié en tant qu'EXPÉDITEUR
    Quand je consulte le QR code de l'offre "offre-track-7"
    Alors la réponse HTTP est 200
    Etant donné l'utilisateur "traveler-track-007" est authentifié en tant que VOYAGEUR
    Quand je scanne un événement "TRANSIT" sur l'offre "offre-track-7"
    Alors la réponse HTTP est 201
    Quand je consulte les événements de l'offre "offre-track-7"
    Alors la réponse HTTP est 200
    Et la réponse est une liste non vide

  @happy-path @skip
  Scénario: Consultation des événements de suivi d'une offre
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-track-006" et le téléphone "+33688000011"
    Et il existe une annonce de "Lyon" à "Abidjan" avec 15 kg disponibles à 6.0 €/kg sauvegardée sous "annonce-track-6"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-track-006" et le téléphone "+33688000012"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-track-6"
    Et l'offre "offre-track-6" est sauvegardée
    Etant donné l'utilisateur "traveler-track-006" est authentifié en tant que VOYAGEUR
    Et le paiement de l'offre "offre-track-6" est validé
    Et j'accepte l'offre "offre-track-6"
    Et je scanne un événement "TRANSIT" sur l'offre "offre-track-6"
    Quand je consulte les événements de l'offre "offre-track-6"
    Alors la réponse HTTP est 200
    Et la réponse contient 1 événements de suivi
