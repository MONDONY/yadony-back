package com.yadony.api.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentsTest {

    private static final String IOS = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)";
    private static final String ANDROID = "Mozilla/5.0 (Linux; Android 14; SM-A156B)";
    private static final String DESKTOP = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5)";

    @Test
    void mobileOs_readsTheUserAgent() {
        assertThat(UserAgents.mobileOs(IOS)).isEqualTo(UserAgents.MobileOs.IOS);
        assertThat(UserAgents.mobileOs("Mozilla/5.0 (iPad; CPU OS 17_0)")).isEqualTo(UserAgents.MobileOs.IOS);
        assertThat(UserAgents.mobileOs(ANDROID)).isEqualTo(UserAgents.MobileOs.ANDROID);
        assertThat(UserAgents.mobileOs(DESKTOP)).isEqualTo(UserAgents.MobileOs.OTHER);
        assertThat(UserAgents.mobileOs(null)).isEqualTo(UserAgents.MobileOs.OTHER);
        assertThat(UserAgents.mobileOs(" ")).isEqualTo(UserAgents.MobileOs.OTHER);
    }

    @Test
    void storeUrlFor_picksTheDeviceStore_andNeverADeadButton() {
        String ios = "https://apps.apple.com/app/yadony";
        String android = "https://play.google.com/store/apps/details?id=com.yadony";
        assertThat(UserAgents.storeUrlFor(IOS, ios, android)).isEqualTo(ios);
        assertThat(UserAgents.storeUrlFor(ANDROID, ios, android)).isEqualTo(android);
        assertThat(UserAgents.storeUrlFor(DESKTOP, ios, android)).isNull();
        assertThat(UserAgents.storeUrlFor(IOS, "", android)).isNull();
        assertThat(UserAgents.storeUrlFor(IOS, "null", android)).isNull();
        assertThat(UserAgents.storeUrlFor(ANDROID, ios, " none ")).isNull();
        assertThat(UserAgents.storeUrlFor(ANDROID, ios, "-")).isNull();
        assertThat(UserAgents.storeUrlFor(ANDROID, ios, null)).isNull();
        assertThat(UserAgents.configuredUrl("  " + ios + " ")).isEqualTo(ios);
    }
}
