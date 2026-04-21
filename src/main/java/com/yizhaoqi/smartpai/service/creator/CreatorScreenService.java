package com.yizhaoqi.smartpai.service.creator;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 博主账号筛选器。
 *
 * <p>来源：openclaw 备份里 {@code xiaohongshu-outreach-bridge/batch_comment_v2.mjs}
 * 中的 {@code screenProfile} + {@code getProfileInfo} 正则；原项目用了 1 年多，
 * 在 Tory Burch 品牌外联场景下 precision/recall 都验证过。
 *
 * <p>能力：
 * <ol>
 *   <li>识别"商业号 / 真实用户"：基于词库双维度打分，给出 verdict = Y / N / ?</li>
 *   <li>从 bio / 主页文本里抽取 email / 微信号 / 手机号</li>
 * </ol>
 *
 * <p>设计：纯函数，没有 IO，也不依赖数据库；
 * 通过 {@code creator_screen} tool 暴露给 agent 使用。
 */
@Service
public class CreatorScreenService {

    /**
     * 商业 / 非真实用户信号词库。
     * 来自 openclaw 备份 batch_comment_v2.mjs:42-51。
     */
    public static final List<String> COMMERCIAL_KEYWORDS = List.of(
            "柜姐", "柜哥", "专柜", "导购", "合作", "商务", "品牌", "官方", "旗舰",
            "代购", "跑腿", "推广", "种草", "接单", "报价", "询价", "私信下单",
            "店铺", "门店", "专卖", "经销", "批发", "零售", "采购",
            "新闻", "资讯", "媒体", "编辑", "记者", "杂志",
            "粉丝团", "后援会", "应援", "打call",
            "每日更新", "天天更新", "好物分享", "好物推荐",
            "活动", "随拍", "展示", "专业讲解",
            "欢迎留言", "感谢关注", "福利多多"
    );

    /**
     * 个人 / 真实用户信号词库。
     * 来自 openclaw 备份 batch_comment_v2.mjs:53-59。
     */
    public static final List<String> PERSONAL_KEYWORDS = List.of(
            "水瓶", "白羊", "金牛", "双子", "巨蟹", "狮子", "处女",
            "天秤", "天蝎", "射手", "摩羯", "双鱼",
            "INTP", "INTJ", "INFP", "INFJ", "ENTP", "ENTJ", "ENFP", "ENFJ",
            "ISTP", "ISTJ", "ISFP", "ISFJ", "ESTP", "ESTJ", "ESFP", "ESFJ",
            "大学", "University", "学院",
            "吃不胖", "健身", "旅行", "潜水", "生活", "日常",
            "坐标", "一枚", "宝妈", "打工人"
    );

    // 联系方式正则
    private static final Pattern EMAIL = Pattern.compile("[\\w.-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final List<Pattern> WECHAT_PATTERNS = List.of(
            Pattern.compile("(?i)(?:微信|wx|vx|WeChat|wechat)[号]?\\s*[:：]\\s*([A-Za-z0-9_\\-]{5,20})"),
            Pattern.compile("(?i)(?:微信|wx|vx|WeChat|wechat)[号]?\\s+([A-Za-z0-9_\\-]{5,20})"),
            Pattern.compile("(?:v|V|💬)\\s*[:：]\\s*([A-Za-z0-9_\\-]{5,20})")
    );

    /**
     * 给博主打筛选分。
     *
     * @param bio       博主 bio（可空）
     * @param nickname  昵称（可空）
     * @param pageText  主页纯文本抓出来的 text（可空）
     * @return 筛选结果（含 verdict / 双维度 score / 匹配到的关键词）
     */
    public ScreenResult screen(String bio, String nickname, String pageText) {
        String combined = (safe(pageText) + ' ' + safe(nickname) + ' ' + safe(bio)).toLowerCase();

        List<String> commercialMatches = new ArrayList<>();
        List<String> personalMatches = new ArrayList<>();

        for (String kw : COMMERCIAL_KEYWORDS) {
            if (combined.contains(kw.toLowerCase())) commercialMatches.add(kw);
        }
        for (String kw : PERSONAL_KEYWORDS) {
            if (combined.contains(kw.toLowerCase())) personalMatches.add(kw);
        }

        // verdict 判定逻辑（对齐 Jenny 原实现）
        String verdict;
        if (commercialMatches.size() >= 2) {
            verdict = "N";
        } else if (!personalMatches.isEmpty() && commercialMatches.isEmpty()) {
            verdict = "Y";
        } else if (commercialMatches.size() <= 1) {
            // 只要商业词 ≤1 且已过平台侧的分层过滤，倾向判真人
            verdict = "Y";
        } else {
            verdict = "?";
        }

        return new ScreenResult(
                verdict,
                commercialMatches.size(),
                personalMatches.size(),
                commercialMatches,
                personalMatches);
    }

    /**
     * 从一段任意文本里抓联系方式。优先在 bio+页面 3000 字内找，避免污染。
     *
     * @param source 页面文本（如 bio、主页 innerText 片段）
     * @return 联系方式三元组（找不到就是空串）
     */
    public ContactInfo extractContacts(String source) {
        if (source == null || source.isBlank()) return new ContactInfo("", "", "");
        // 限制到前 3000 字，减少噪声
        String text = source.length() > 3000 ? source.substring(0, 3000) : source;

        String email = firstMatch(EMAIL, text, 0);
        String phone = firstMatch(PHONE, text, 0);
        String wechat = "";
        for (Pattern p : WECHAT_PATTERNS) {
            String w = firstMatch(p, text, 1);
            if (!w.isEmpty()) {
                wechat = w;
                break;
            }
        }
        return new ContactInfo(email, wechat, phone);
    }

    /** 判断是否有至少一项有效联系方式 */
    public boolean hasAnyContact(ContactInfo c) {
        return c != null && (!c.email().isEmpty() || !c.wechat().isEmpty() || !c.phone().isEmpty());
    }

    // ---------- helpers ----------

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String firstMatch(Pattern p, String text, int group) {
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(group);
        }
        return "";
    }

    // ---------- 传输对象 ----------

    public record ScreenResult(String verdict,
                               int commercialScore,
                               int personalScore,
                               List<String> commercialMatches,
                               List<String> personalMatches) {}

    public record ContactInfo(String email, String wechat, String phone) {}
}
