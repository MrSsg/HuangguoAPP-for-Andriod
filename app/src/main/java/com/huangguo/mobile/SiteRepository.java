package com.huangguo.mobile;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class SiteRepository {
    private static final String ORIGIN = "https://huangguoai.com";
    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList(
            "ai-duanju", "ai-manju", "ai-huanlian", "ai-mogai"));
    private static final byte[] COVER_KEY = "f5d965df75336270".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COVER_IV = "97b60374abc2fbe1".getBytes(StandardCharsets.US_ASCII);
    private final File coverDir;
    private final Context appContext;

    SiteRepository(Context context) {
        appContext = context.getApplicationContext();
        coverDir = new File(context.getCacheDir(), "covers");
        if (!coverDir.exists()) coverDir.mkdirs();
    }

    private byte[] fetch(String url) throws Exception {
        URL parsed = new URL(url);
        if (!"https".equals(parsed.getProtocol())) throw new IllegalArgumentException("仅支持 HTTPS");
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(25_000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36");
        connection.setRequestProperty("Referer", ORIGIN + "/");
        connection.setUseCaches(false);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("站点请求失败（" + status + "）");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return bytes.toByteArray();
            }
        } catch (IOException error) {
            throw new IOException("站点连接失败", error);
        } finally {
            connection.disconnect();
        }
    }

    private Document page(String path) throws Exception {
        if (!path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("无效路径");
        byte[] body = fetch(ORIGIN + path);
        if (body.length < 1000) throw new IllegalStateException("站点暂时返回空页面");
        return Jsoup.parse(new String(body, StandardCharsets.UTF_8), ORIGIN);
    }

    private static String text(Element element) {
        return element == null ? "" : element.text().replaceAll("\\s+", " ").trim();
    }

    private static String attr(Element element, String name) {
        return element == null ? "" : element.attr(name);
    }

    private static String cover(Element card) {
        Element image = card.selectFirst(".hg-drama-card__cover img");
        String value = attr(image, "data-src");
        return value.isEmpty() ? attr(image, "src") : value;
    }

    private static JSONArray cards(Element document, String category) throws Exception {
        JSONArray items = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (Element card : document.select(".hg-drama-card[data-track-id]")) {
            String id = card.attr("data-track-id");
            if (!id.matches("\\d+") || !ids.add(id)) continue;
            Element titleNode = card.selectFirst(".hg-drama-card__title");
            if (titleNode != null) titleNode = titleNode.clone();
            if (titleNode != null) titleNode.select(".sr-only").remove();
            String title = text(titleNode);
            if (title.isEmpty()) title = card.attr("data-track-title");
            if (title.isEmpty()) title = "剧集 " + id;
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("title", title);
            item.put("cover", cover(card));
            item.put("description", text(card.selectFirst(".hg-drama-card__desc")));
            item.put("score", text(card.selectFirst(".hg-drama-card__score")));
            item.put("episodeLabel", text(card.selectFirst(".hg-drama-card__episode")));
            String mappedCategory = category;
            if (mappedCategory.isEmpty()) {
                switch (card.attr("data-track-type-id")) {
                    case "24": mappedCategory = "ai-duanju"; break;
                    case "25": mappedCategory = "ai-manju"; break;
                    case "26": mappedCategory = "ai-huanlian"; break;
                    case "27": mappedCategory = "ai-mogai"; break;
                    default: break;
                }
            }
            if (!mappedCategory.isEmpty()) item.put("category", mappedCategory);
            JSONArray tags = new JSONArray();
            for (Element tag : card.select(".hg-drama-card__tags .hg-tag")) {
                if (tags.length() >= 3) break;
                Element clean = tag.clone();
                clean.select(".sr-only").remove();
                String value = text(clean);
                if (!value.isEmpty()) tags.put(value);
            }
            item.put("tags", tags);
            items.put(item);
        }
        return items;
    }

    private static JSONObject listing(Document document, int currentPage, String category) throws Exception {
        JSONObject result = new JSONObject();
        result.put("items", cards(document, category));
        int next = Integer.MAX_VALUE;
        for (Element link : document.select(".hg-pager a[href]")) {
            String href = link.attr("href");
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("/(\\d+)/$").matcher(href);
            if (!match.find()) continue;
            int page = Integer.parseInt(match.group(1));
            if (page > currentPage && page < next) next = page;
        }
        result.put("nextPage", next == Integer.MAX_VALUE ? JSONObject.NULL : next);
        return result;
    }

    JSONObject home() throws Exception {
        Document root = page("/");
        JSONArray featured = new JSONArray();
        Element heroData = root.selectFirst("[data-hero-slides]");
        if (heroData != null) {
            try {
                JSONArray slides = new JSONArray(heroData.data().isEmpty() ? heroData.text() : heroData.data());
                for (int i = 0; i < slides.length(); i++) {
                    JSONObject slide = slides.optJSONObject(i);
                    if (slide == null || slide.optBoolean("isAd") || slide.optInt("adId") != 0) continue;
                    String href = slide.optString("href");
                    java.util.regex.Matcher match = java.util.regex.Pattern.compile("^/video/(\\d+)/").matcher(href);
                    if (!match.find()) continue;
                    JSONObject item = new JSONObject();
                    item.put("id", match.group(1));
                    item.put("title", slide.optString("title"));
                    item.put("cover", slide.optString("cover"));
                    item.put("description", slide.optString("desc"));
                    item.put("score", slide.optString("score"));
                    item.put("episodeLabel", slide.optString("episode"));
                    featured.put(item);
                }
            } catch (Exception ignored) { /* 无轮播时展示推荐目录 */ }
        }
        JSONObject result = new JSONObject();
        result.put("featured", featured);
        result.put("items", cards(root, ""));
        for (Element section : root.select("main section.hg-section")) {
            String heading = text(section.selectFirst("h2"));
            if ("精选推荐".equals(heading)) result.put("homepageRecommend", cards(section, ""));
            if ("最近上新".equals(heading)) result.put("homepageNewest", cards(section, ""));
        }
        try { result.put("recommend", listing(page("/recommend"), 1, "")); }
        catch (Exception error) { result.put("recommend", new JSONObject().put("items", result.getJSONArray("items")).put("nextPage", JSONObject.NULL)); }
        try { result.put("newest", listing(page("/newest"), 1, "")); }
        catch (Exception error) { result.put("newest", new JSONObject().put("items", new JSONArray()).put("nextPage", JSONObject.NULL)); }
        if (featured.length() == 0 && result.getJSONArray("items").length() == 0) throw new IllegalStateException("首页内容暂时不可用");
        return result;
    }

    JSONObject category(String id, int pageNumber) throws Exception {
        if (!CATEGORIES.contains(id) || pageNumber < 1 || pageNumber > 100) throw new IllegalArgumentException("无效分类");
        String path = "/" + id + "/" + (pageNumber == 1 ? "" : pageNumber + "/");
        JSONObject result = listing(page(path), pageNumber, id);
        if (result.getJSONArray("items").length() == 0) throw new IllegalStateException("分类内容暂时不可用");
        return result;
    }

    JSONObject tags() throws Exception {
        JSONArray bundled;
        try (InputStream input = appContext.getAssets().open("tags.json"); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            bundled = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        JSONArray items = new JSONArray();
        try {
            String xml = new String(fetch(ORIGIN + "/sitemap/tag/1.xml"), StandardCharsets.UTF_8);
            Document sitemap = Jsoup.parse(xml, "", Parser.xmlParser());
            Set<String> current = new LinkedHashSet<>();
            for (Element loc : sitemap.select("loc")) {
                java.util.regex.Matcher match = java.util.regex.Pattern.compile("^https://huangguoai\\.com/tag/([a-z0-9-]+)/$").matcher(text(loc));
                if (match.matches()) current.add(match.group(1));
            }
            if (!current.isEmpty()) {
                for (int i = 0; i < bundled.length(); i++) {
                    JSONObject tag = bundled.optJSONObject(i);
                    if (tag != null && current.remove(tag.optString("slug"))) items.put(tag);
                }
                for (String slug : current) {
                    String name = slug;
                    try { name = text(page("/tag/" + slug + "/").selectFirst("h1")).replaceFirst("短剧在线观看$", "").trim(); }
                    catch (Exception ignored) { /* 新标签暂用链接名称，仍可进入列表 */ }
                    items.put(new JSONObject().put("slug", slug).put("name", name).put("group", "other"));
                }
            }
        } catch (Exception ignored) { /* 离线时沿用内置标签 */ }
        if (items.length() == 0) items = bundled;
        return new JSONObject().put("items", items);
    }

    JSONObject tagListing(String slug, int pageNumber) throws Exception {
        if (slug == null || !slug.matches("[a-z0-9-]{1,80}") || pageNumber < 1 || pageNumber > 500) {
            throw new IllegalArgumentException("无效标签或页码");
        }
        String path = "/tag/" + slug + "/" + (pageNumber == 1 ? "" : "page/" + pageNumber + "/");
        return listing(page(path), pageNumber, "");
    }

    JSONObject recommend(int pageNumber, boolean newest) throws Exception {
        if (pageNumber < 1) throw new IllegalArgumentException("无效页码");
        String name = newest ? "newest" : "recommend";
        return listing(page("/" + name + (pageNumber == 1 ? "" : "/" + pageNumber + "/")), pageNumber, "");
    }

    JSONObject search(String term) throws Exception {
        String query = term.trim();
        if (query.length() > 80) query = query.substring(0, 80);
        if (query.isEmpty()) return new JSONObject().put("items", new JSONArray()).put("nextPage", JSONObject.NULL);
        return listing(page("/search/?keyword=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)), 1, "");
    }

    JSONObject detail(String id) throws Exception {
        if (!id.matches("\\d+")) throw new IllegalArgumentException("无效剧集");
        Document document = page("/detail/" + id + "/");
        Element root = document.selectFirst(".hg-web-detail");
        if (root == null) throw new IllegalStateException("未找到剧集详情");
        JSONObject result = new JSONObject();
        result.put("id", id);
        result.put("title", text(root.selectFirst("h1")));
        Element image = root.selectFirst(".hg-web-detail__poster img");
        String cover = attr(image, "data-src");
        result.put("cover", cover.isEmpty() ? attr(image, "src") : cover);
        result.put("description", text(root.selectFirst(".hg-web-detail__desc")));
        result.put("score", text(root.selectFirst(".hg-web-detail__score")));
        result.put("episodeLabel", text(root.selectFirst(".hg-web-detail__episode")));
        JSONArray tags = new JSONArray();
        for (Element tag : root.select(".hg-web-detail__tags a")) {
            if (tags.length() >= 5) break;
            String value = text(tag);
            if (!value.isEmpty()) tags.put(value);
        }
        result.put("tags", tags);
        JSONArray episodes = new JSONArray();
        for (Element link : root.select(".hg-web-detail__ep-grid a[href]")) {
            String href = link.attr("href");
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("^/video/" + id + "/(?:ep-(\\d+)/)?$").matcher(href);
            if (!match.find()) continue;
            int number = match.group(1) == null ? 1 : Integer.parseInt(match.group(1));
            episodes.put(new JSONObject().put("number", number).put("url", ORIGIN + href));
        }
        if (episodes.length() == 0 && root.selectFirst("a[href=/video/" + id + "/]") != null) {
            episodes.put(new JSONObject().put("number", 1).put("url", ORIGIN + "/video/" + id + "/"));
        }
        result.put("episodes", episodes);
        return result;
    }

    JSONObject episode(String id, int number) throws Exception {
        if (!id.matches("\\d+") || number < 1) throw new IllegalArgumentException("无效选集");
        String path = "/video/" + id + "/" + (number == 1 ? "" : "ep-" + number + "/");
        Document document = page(path);
        Element node = document.selectFirst("#videoInitialData");
        if (node == null) throw new IllegalStateException("未取得播放信息");
        JSONObject data = new JSONObject(node.data().isEmpty() ? node.text() : node.data());
        if (!id.equals(String.valueOf(data.opt("id"))) || !data.optString("videoSrc").startsWith("https://")) {
            throw new IllegalStateException("未取得有效播放地址");
        }
        JSONObject result = new JSONObject();
        result.put("id", id);
        result.put("episode", number);
        result.put("title", data.optString("title", "剧集 " + id));
        result.put("source", data.optString("videoSrc"));
        result.put("cover", data.optString("coverSrc", data.optString("posterSrc")));
        result.put("nextUrl", attr(document.selectFirst(".hg-web-play [data-web-play-ep-next][href]"), "href"));
        return result;
    }

    String coverData(String value) throws Exception {
        URL url = new URL(value);
        if (!"https".equals(url.getProtocol()) ||
                !("pic.fisawck.cn".equals(url.getHost()) || "pic.tkzdds.cn".equals(url.getHost()))) {
            throw new IllegalArgumentException("无效封面地址");
        }
        String path = url.getPath();
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        if (!Arrays.asList("jpg", "jpeg", "png", "webp", "gif").contains(extension)) throw new IllegalArgumentException("不支持的封面格式");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(path.getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 16; i++) key.append(String.format("%02x", hash[i] & 0xff));
        File file = new File(coverDir, key + ".img");
        byte[] decoded;
        if (file.isFile()) {
            decoded = java.nio.file.Files.readAllBytes(file.toPath());
            if (repairCoverHeader(decoded)) {
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(decoded); }
            }
            if (!validImage(decoded)) {
                file.delete();
                decoded = null;
            } else file.setLastModified(System.currentTimeMillis());
        } else decoded = null;
        if (decoded == null) {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(COVER_KEY, "AES"), new IvParameterSpec(COVER_IV));
            decoded = cipher.doFinal(fetch(value));
            repairCoverHeader(decoded);
            if (!validImage(decoded)) throw new IllegalStateException("封面解密结果无效");
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(decoded); }
            trimCoverCache();
        }
        String mime = decoded[0] == (byte) 0xff ? "image/jpeg" :
                decoded[0] == (byte) 0x89 ? "image/png" : decoded[0] == 'G' ? "image/gif" : "image/webp";
        return "data:" + mime + ";base64," + Base64.encodeToString(decoded, Base64.NO_WRAP);
    }

    private static boolean repairCoverHeader(byte[] bytes) {
        // Current CDN payloads differ from the valid Windows cache at byte offset 6 by XOR 0x0E.
        // Check the surrounding format signature so an already-normalized cache is not changed twice.
        if (bytes.length < 12) return false;
        boolean png = bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G' &&
                bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x14 && bytes[7] == 0x0a;
        boolean jpeg = bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
        boolean jfif = jpeg && bytes[3] == (byte) 0xe0 && bytes[6] == 'D' &&
                bytes[7] == 'F' && bytes[8] == 'I' && bytes[9] == 'F' && bytes[10] == 0;
        boolean dqt = jpeg && bytes[3] == (byte) 0xdb && (bytes[6] & 0xfc) == 0x0c;
        boolean exif = jpeg && bytes[3] == (byte) 0xe1 && bytes[6] == 'K' &&
                bytes[7] == 'x' && bytes[8] == 'i' && bytes[9] == 'f';
        boolean icc = jpeg && bytes[3] == (byte) 0xe2 && bytes[6] == 'G' &&
                bytes[7] == 'C' && bytes[8] == 'C' && bytes[9] == '_';
        if (!(png || jfif || dqt || exif || icc)) return false;
        bytes[6] ^= 0x0e;
        return true;
    }

    private static boolean validImage(byte[] bytes) {
        if (bytes.length < 16) return false;
        return (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8) ||
                (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G' &&
                        bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) ||
                (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' &&
                        bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') ||
                (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F');
    }

    private void trimCoverCache() {
        File[] files = coverDir.listFiles();
        if (files == null) return;
        long bytes = 0;
        for (File file : files) bytes += file.length();
        if (bytes <= 150L * 1024 * 1024) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (bytes <= 150L * 1024 * 1024) break;
            long size = file.length();
            if (file.delete()) bytes -= size;
        }
    }

    void clearCovers() {
        File[] files = coverDir.listFiles();
        if (files != null) for (File file : files) file.delete();
    }
}
