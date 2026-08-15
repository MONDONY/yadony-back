# language: fr
@regression @cancellation
Fonctionnalité: Annulation d'un voyage
  En tant que voyageur
  Je veux pouvoir annuler un voyage
  Et permettre aux expéditeurs affectés de trouver une alternative

  @happy-path @critical
  Scénario: Annulation d'un voyage sans offres acceptées
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-cancel-001" et le téléphone "+33700000001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-cancel-1"
    Quand j'annule l'annonce "annonce-cancel-1" avec la raison "Voyage annulé pour raisons personnelles"
    Alors la réponse HTTP est 201
    Et la réponse indique que 0 offres ont été annulées

  @happy-path @critical
  Scénario: Annulation avec offres acceptées — cascade vers les offres
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-cancel-002" et le téléphone "+33700000002"
    Et il existe une annonce de "Lyon" à "Abidjan" avec 20 kg disponibles à 6.0 €/kg sauvegardée sous "annonce-cancel-2"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-cancel-002" et le téléphone "+33700000003"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-cancel-2"
    Et l'offre "offre-cancel-2" est sauvegardée
    Etant donné l'utilisateur "traveler-cancel-002" est authentifié en tant que VOYAGEUR
    Et le paiement de l'offre "offre-cancel-2" est validé
    Et j'accepte l'offre "offre-cancel-2"
    Quand j'annule l'annonce "annonce-cancel-2" avec la raison "Problème médical urgent"
    Alors la réponse HTTP est 201
    Et la réponse indique que 1 offres ont été annulées

  @error-case
  Scénario: Annulation d'une annonce déjà annulée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-cancel-003" et le téléphone "+33700000004"
    Et il existe une annonce de "Marseille" à "Bamako" avec 15 kg disponibles à 4.5 €/kg sauvegardée sous "annonce-cancel-3"
    Et j'annule l'annonce "annonce-cancel-3" avec la raison "Premier motif"
    Quand j'annule l'annonce "annonce-cancel-3" avec la raison "Second motif"
    Alors la réponse HTTP est 409
    Et le code d'erreur de la réponse est "already-cancelled"

  @error-case
  Scénario: Annulation d'une annonce appartenant à un autre voyageur
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-cancel-004" et le téléphone "+33700000005"
    Et il existe une annonce de "Paris" à "Douala" avec 10 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-cancel-4"
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "intruder-001" et le téléphone "+33700000099"
    Quand j'annule l'annonce "annonce-cancel-4" avec la raison "Je veux annuler l'annonce d'un autre"
    Alors la réponse HTTP est 403
    Et le code d'erreur de la réponse est "forbidden"

  @happy-path
  Scénario: Signalement d'un no-show cash confirmé par l'administrateur
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "ns-traveler-001" et le téléphone "+33700000010"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-ns-1"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "ns-sender-001" et le téléphone "+33700000011"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-ns-1"
    Et l'offre "offre-ns-1" est sauvegardée
    Et l'offre "offre-ns-1" est une remise cash dont l'horaire est dépassé
    Etant donné l'utilisateur "ns-traveler-001" est authentifié en tant que VOYAGEUR
    Quand le voyageur signale un no-show pour l'offre "offre-ns-1"
    Alors la réponse HTTP est 200
    Etant donné l'utilisateur "ns-admin-001" est authentifié en tant qu'ADMIN
    Quand l'administrateur confirme le no-show pour l'offre "offre-ns-1"
    Alors la réponse HTTP est 200

  @happy-path
  Scénario: Contestation d'un no-show par l'expéditeur et consultation des litiges
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "ns-traveler-002" et le téléphone "+33700000012"
    Et il existe une annonce de "Lyon" à "Abidjan" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-ns-2"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "ns-sender-002" et le téléphone "+33700000013"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-ns-2"
    Et l'offre "offre-ns-2" est sauvegardée
    Et l'offre "offre-ns-2" est une remise cash dont l'horaire est dépassé
    Etant donné l'utilisateur "ns-traveler-002" est authentifié en tant que VOYAGEUR
    Quand le voyageur signale un no-show pour l'offre "offre-ns-2"
    Alors la réponse HTTP est 200
    Etant donné l'utilisateur "ns-sender-002" est authentifié en tant qu'EXPÉDITEUR
    Quand l'expéditeur conteste le no-show pour l'offre "offre-ns-2"
    Alors la réponse HTTP est 200
    Etant donné l'utilisateur "ns-traveler-002" est authentifié en tant que VOYAGEUR
    Quand je consulte mes litiges
    Alors la réponse HTTP est 200

  @happy-path @critical
  Scénario: L'expéditeur signale un voyageur absent après la date limite de dépôt
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "tns-traveler-001" et le téléphone "+33700000020"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-tns-1"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "tns-sender-001" et le téléphone "+33700000021"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-tns-1"
    Et l'offre "offre-tns-1" est sauvegardée
    Et l'offre "offre-tns-1" est une remise cash dont l'horaire est dépassé
    Etant donné l'utilisateur "tns-sender-001" est authentifié en tant qu'EXPÉDITEUR
    Quand l'expéditeur signale le voyageur absent pour l'offre "offre-tns-1"
    Alors la réponse HTTP est 200
    Et l'offre "offre-tns-1" passe au statut "NO_SHOW" en base
