-- V265 : traduction anglaise des questions frequentes du support.
--
-- L'app existe maintenant en anglais et envoie Accept-Language, mais
-- GET /support/replies ne renvoyait que le francais code en dur en V244.
-- Colonnes nullables : une reponse sans traduction retombe sur le francais
-- (voir SupportPredefinedReplyResponse), rien ne casse tant que ce n'est pas
-- rempli.

ALTER TABLE support_predefined_replies ADD COLUMN IF NOT EXISTS question_en VARCHAR(300);
ALTER TABLE support_predefined_replies ADD COLUMN IF NOT EXISTS answer_en TEXT;

UPDATE support_predefined_replies SET
    question_en = 'Why does my account need to be verified?',
    answer_en = 'Identity verification protects both parties: it confirms that the person you are trusting with a parcel, or who is trusting you with one, is really who they claim to be. It takes a few minutes and is only asked once.'
WHERE code = 'account-verification';

UPDATE support_predefined_replies SET
    question_en = 'My identity verification failed, what should I do?',
    answer_en = 'Restart the verification from your profile, making sure to photograph a document that is still valid, laid flat, fully inside the frame and without glare. If it fails again, open a ticket and we will review it manually.'
WHERE code = 'kyc-rejected';

UPDATE support_predefined_replies SET
    question_en = 'When am I charged?',
    answer_en = 'You are charged when the traveler accepts your offer. The amount stays on hold with our payment provider: the traveler is only paid once the recipient confirms delivery.'
WHERE code = 'payment-when-charged';

UPDATE support_predefined_replies SET
    question_en = 'When will my refund arrive?',
    answer_en = 'A refund is sent back to your original payment method and usually appears within 5 to 10 business days, depending on your bank.'
WHERE code = 'payment-refund-delay';

UPDATE support_predefined_replies SET
    question_en = 'I am a traveler, when do I get paid?',
    answer_en = 'The transfer is sent as soon as the recipient confirms delivery, then follows the usual bank processing times. If no one confirms, the payout is triggered automatically 48 hours after the expected delivery date.'
WHERE code = 'payout-delay';

UPDATE support_predefined_replies SET
    question_en = 'How does the parcel drop-off work?',
    answer_en = 'At both drop-off and delivery, a QR code is scanned by both parties. This scan timestamps the exchange and serves as proof in case of a dispute: never hand over a parcel without scanning.'
WHERE code = 'delivery-qr-scan';

UPDATE support_predefined_replies SET
    question_en = 'My parcel has not arrived on the expected date.',
    answer_en = 'First contact the traveler from the conversation linked to the parcel: a delayed flight or a customs check explains most delays. If you get no response within 48 hours, open a ticket and we will step in.'
WHERE code = 'delivery-late';

UPDATE support_predefined_replies SET
    question_en = 'The traveler canceled their trip, what happens now?',
    answer_en = 'You are refunded in full and we automatically suggest other travelers on the same trip.'
WHERE code = 'trip-cancelled';

UPDATE support_predefined_replies SET
    question_en = 'What can I send?',
    answer_en = 'The following are forbidden: cash, jewelry and valuables, prescription medication, perishable goods, lithium batteries outside a device, weapons and any illegal product. The declared value of a parcel is capped at 500 euros.'
WHERE code = 'package-forbidden-items';

UPDATE support_predefined_replies SET
    question_en = 'How do I delete my account?',
    answer_en = 'From Profile, then Settings, then Delete my account. The deletion is permanent once your ongoing parcels and trips are finished.'
WHERE code = 'account-delete';
