package com.example.be.domain.collection.cluster;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A shared manufacturer cannot bridge explicitly different development and customer projects. */
final class BusinessProjectConflictEvidence {
    private static final String NAME = "[가-힣a-z][가-힣a-z0-9&.-]{1,34}?";
    private static final Pattern JOINT = Pattern.compile(
            "(?<![가-힣a-z0-9])(" + NAME + ")(?:와|과)\\s+([^.!?\\n]{2,80}?)(?:은|는|이|가)\\s+");
    private static final Pattern CUSTOMER = Pattern.compile(
            "(?<![가-힣a-z0-9])(" + NAME + ")(?:은|는|이|가)\\s+(" + NAME + ")의\\s+");
    private static final Pattern CHANGED_SUBJECT = Pattern.compile(
            "(?<![가-힣a-z0-9])" + NAME + "(?:은|는|이|가)\\s+");
    private static final Pattern DEVELOPMENT = Pattern.compile(
            "(?:공동\\s*)?개발(?:하고\\s*있|한다|했다|하였|에\\s*(?:착수|나섰)|을\\s*(?:시작|추진))");
    private static final Pattern PRODUCTION = Pattern.compile(
            "(?:시제품|시험|양산|생산).{0,45}(?:돌입|착수|시작|진행|개시|완료)|(?:생산|양산)(?:한다|했다|하고\\s*있)");
    private static final Pattern PRODUCT = Pattern.compile("칩|반도체|프로세서|배터리|전기차|자동차|부품|플랫폼");
    private static final Pattern BACKGROUND = Pattern.compile("(?:^|[.!?]\\s+|\\n+)\\s*(?:한편|앞서|과거|참고로)\\s");
    private static final Pattern UNCERTAIN = Pattern.compile("가능성|것으로\\s*(?:예상|전망|관측)|개발설|생산설");
    private static final Pattern DENIED = Pattern.compile(
            "부인|사실무근|취소|철회|중단|않|아니|못했|못한다|없다고|없었다");
    private static final Set<String> GENERAL = Set.of(
            "회사", "기업", "업체", "업계", "정부", "시장", "고객", "파트너", "관계자", "공장", "연구팀",
            "자사", "당사", "이들", "양사", "당국", "제품", "이번", "기술", "반도체");
    private final Map<Long, Profile> profiles = new HashMap<>();

    BusinessProjectConflictEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            if (!article.hasFullText()) {
                continue;
            }
            String title = normalize(detector.coreTitle(article.title()));
            String body = normalize(ArticleEvidenceText.foreground(article.body(), 1000));
            Matcher background = BACKGROUND.matcher(body);
            if (background.find()) {
                body = body.substring(0, background.start());
            }
            Profile profile = profile(title, body);
            if (profile != null) {
                profiles.put(article.articleId(), profile);
            }
        }
    }

    boolean conflicts(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null || a.action().equals(b.action())) {
            return false;
        }
        Profile production = a.action().equals("production") ? a : b;
        Profile development = a.action().equals("development") ? a : b;
        return development.parties().contains(production.maker())
                && !development.parties().equals(production.parties());
    }

    private static Profile profile(String title, String body) {
        Profile result = null;
        // Only a sentence naming both headline parties may establish a focal project.
        for (String sentence : body.split("[.!?\\n]+")) {
            if (UNCERTAIN.matcher(sentence).find() || DENIED.matcher(sentence).find()
                    || !PRODUCT.matcher(sentence).find()) {
                continue;
            }
            Matcher joint = JOINT.matcher(sentence);
            while (joint.find()) {
                String[] words = joint.group(2).strip().split("\\s+");
                String partner = words[words.length - 1];
                if (boundAction(DEVELOPMENT, sentence.substring(joint.end()))) {
                    Profile candidate = candidate(title, joint.group(1), partner, "development");
                    if (candidate != null) {
                        if (result != null && !result.equals(candidate)) {
                            return null;
                        }
                        result = candidate;
                    }
                }
            }
            Matcher customer = CUSTOMER.matcher(sentence);
            while (customer.find()) {
                if (boundAction(PRODUCTION, sentence.substring(customer.end()))) {
                    Profile candidate = candidate(title, customer.group(1), customer.group(2), "production");
                    if (candidate != null) {
                        if (result != null && !result.equals(candidate)) {
                            return null;
                        }
                        result = candidate;
                    }
                }
            }
        }
        return result;
    }

    private static boolean boundAction(Pattern action, String tail) {
        Matcher matcher = action.matcher(tail);
        return matcher.find() && !CHANGED_SUBJECT.matcher(tail.substring(0, matcher.start())).find();
    }

    private static Profile candidate(String title, String rawMaker, String rawPartner, String action) {
        if (!rawPartner.matches(NAME) || GENERAL.contains(rawMaker) || GENERAL.contains(rawPartner)) {
            return null;
        }
        String maker = DeterministicEntityExtractor.canonicalSubject(rawMaker);
        String partner = DeterministicEntityExtractor.canonicalSubject(rawPartner);
        boolean headlineParties = namesMaker(title, maker) && names(title, partner);
        if (action.equals("development")) {
            headlineParties |= namesMaker(title, partner) && names(title, maker);
        }
        if (maker.equals(partner) || !headlineParties) {
            return null;
        }
        return new Profile(Set.of(maker, partner), action.equals("production") ? maker : null, action);
    }

    private static boolean namesMaker(String title, String maker) {
        if (names(title, maker)) {
            return true;
        }
        // A shortened headline brand must be the first subject, and the body still
        // supplies the full maker identity. This never adds an organization vote.
        String brand = maker.replaceFirst("(?:전자|반도체|자동차|모터스|케미칼|컴퓨팅)$", "");
        String headline = title.replaceFirst("^(?:\\[[^\\]\\n]{1,20}\\]\\s*)+", "");
        return brand.length() >= 2 && !brand.equals(maker)
                && Pattern.compile("^" + Pattern.quote(brand) + "(?=$|[^가-힣a-z0-9])")
                .matcher(headline).find();
    }

    private static boolean names(String title, String name) {
        return DeterministicEntityExtractor.mentionsSubject(title, name)
                || Pattern.compile("(?<![가-힣a-z0-9])" + Pattern.quote(name)
                        + "(?=칩|반도체|프로세서|배터리|부품)").matcher(title).find();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\([^)]{0,40}\\)", "");
    }

    private record Profile(Set<String> parties, String maker, String action) { }
}
