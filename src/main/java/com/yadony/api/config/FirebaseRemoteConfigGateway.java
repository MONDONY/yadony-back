package com.yadony.api.config;

import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseApp;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;
import com.google.firebase.remoteconfig.Template;
import org.springframework.stereotype.Component;

/**
 * Implementation reelle, sur l'application Firebase par defaut (celle que {@link FirebaseConfig}
 * initialise). Resolue a l'appel et non au demarrage : Firebase peut etre absent (test/ci) sans
 * empecher le back de demarrer.
 */
@Component
public class FirebaseRemoteConfigGateway implements RemoteConfigGateway {

    @Override
    public Template fetch() throws FirebaseRemoteConfigException {
        return instance().getTemplate();
    }

    @Override
    public Template publish(Template template) throws FirebaseRemoteConfigException {
        return instance().publishTemplate(template);
    }

    private FirebaseRemoteConfig instance() throws FirebaseRemoteConfigException {
        if (FirebaseApp.getApps().isEmpty()) {
            throw new FirebaseRemoteConfigException(ErrorCode.UNAVAILABLE,
                    "Firebase n'est pas initialise", null, null, null);
        }
        return FirebaseRemoteConfig.getInstance(FirebaseApp.getInstance());
    }
}
