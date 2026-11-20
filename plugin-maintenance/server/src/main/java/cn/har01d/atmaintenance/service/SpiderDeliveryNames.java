package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.SpiderDelivery;

import java.util.regex.Pattern;

final class SpiderDeliveryNames {
    private static final Pattern VERSION = Pattern.compile(
            "(?i)(?:^|[_ -])v([0-9]{1,6})(?:\\.([0-9]{1,6}))?(?:\\.([0-9]{1,6}))?(?:\\.(?:py|txt))?$");

    private SpiderDeliveryNames() {}

    static boolean localized(String name) {
        return name != null && name.replaceFirst("^(?:影视源|视频源)[_\\s-]*", "").codePoints()
                .anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    }

    static SpiderDelivery renamed(SpiderDelivery delivery, String name) {
        var named = describe(name, null, delivery.candidateSha256(), delivery.iteration(), delivery.verified());
        String filename = named.pluginName() + "_v" + delivery.version() + ".py";
        return new SpiderDelivery(named.pluginName(), delivery.version(), filename,
                named.pluginName() + "_v" + delivery.version() + "_" + (delivery.verified() ? "已验证" : "候选") + ".zip",
                (delivery.verified() ? "交付/" : "候选/") + filename,
                delivery.candidateSha256(), delivery.iteration(), delivery.verified());
    }

    static SpiderDelivery describe(String name, String originalFilename, String sha, int iteration, boolean verified) {
        String clean = name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .replaceAll("\\.{2,}", "_").replaceAll("[ .]+$", "").strip();
        if (clean.isEmpty()) clean = "影视源";
        if (clean.codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN))
            clean = "影视源_" + clean;
        clean = clean.codePoints().limit(60).collect(StringBuilder::new,
                StringBuilder::appendCodePoint, StringBuilder::append).toString();
        var match = VERSION.matcher(originalFilename == null ? "" : originalFilename);
        int major = 1, minor = 0, patch = 0;
        if (match.find()) {
            major = Integer.parseInt(match.group(1));
            minor = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
            patch = match.group(3) == null ? 0 : Integer.parseInt(match.group(3));
        }
        String version = major + "." + minor + "." + (patch + iteration);
        String filename = clean + "_v" + version + ".py";
        String state = verified ? "已验证" : "候选";
        return new SpiderDelivery(clean, version, filename, clean + "_v" + version + "_" + state + ".zip",
                (verified ? "交付/" : "候选/") + filename, sha, iteration, verified);
    }
}
