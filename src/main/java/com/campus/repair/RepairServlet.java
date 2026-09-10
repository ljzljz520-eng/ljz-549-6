package com.campus.repair;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 校园报修异步提交接口。
 *
 * 请求：POST /api/repair，application/x-www-form-urlencoded
 *   - location: 报修地点分类，dorm(宿舍) / teaching(教学楼) / canteen(食堂)
 *   - building: 具体位置（如 7 号楼 302）
 *   - description: 问题描述
 *   - contact: 联系方式
 *
 * 成功响应（200）：
 *   {"code":"OK","message":"报修成功","ticketNo":"WX20260910153012001"}
 *
 * 错误响应（按业务区分 code，HTTP 状态码与之一致）：
 *   400 MISSING_FIELD  字段缺失或为空
 *   400 INVALID_FIELD  字段值不合法（如 location 不在可选范围内）
 *   405 METHOD_NOT_ALLOWED  请求方法不对（仅接受 POST）
 *   503 SERVER_BUSY    服务器忙，请稍后重试
 */
@WebServlet(name = "RepairServlet", urlPatterns = {"/api/repair"})
public class RepairServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Set<String> ALLOWED_LOCATIONS =
            Set.of("dorm", "teaching", "canteen");

    private static final int MAX_BUILDING_LEN = 100;
    private static final int MAX_DESCRIPTION_LEN = 500;
    private static final int MAX_CONTACT_LEN = 50;

    private static final DateTimeFormatter TICKET_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 【演示用】模拟服务端繁忙：每处理 5 个请求，第 6 个返回 503。
     * 生产环境应由线程池/下游服务状态决定，此计数器仅用于演示失败分支。
     */
    private static final int BUSY_EVERY = 6;
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        // 仅接受 POST，其他方法（GET/PUT/DELETE 等）统一返回 405
        if (!"POST".equalsIgnoreCase(req.getMethod())) {
            writeJson(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    errorBody("METHOD_NOT_ALLOWED",
                            "请求方法不对，本接口仅支持 POST。"));
            return;
        }
        super.service(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        req.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String location = trim(req.getParameter("location"));
        String building = trim(req.getParameter("building"));
        String description = trim(req.getParameter("description"));
        String contact = trim(req.getParameter("contact"));

        // 1) 必填校验：字段缺失或为空 —— 400 MISSING_FIELD
        if (location.isEmpty()) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                    errorBody("MISSING_FIELD", "请选择报修地点类型。", "location"));
            return;
        }
        if (building.isEmpty()) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                    errorBody("MISSING_FIELD", "请填写具体位置。", "building"));
            return;
        }
        if (description.isEmpty()) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                    errorBody("MISSING_FIELD", "请填写问题描述。", "description"));
            return;
        }
        if (contact.isEmpty()) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                    errorBody("MISSING_FIELD", "请填写联系方式。", "contact"));
            return;
        }

        // 2) 字段合法性校验 —— 400 INVALID_FIELD
        if (!ALLOWED_LOCATIONS.contains(location)) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                    errorBody("INVALID_FIELD", "报修地点类型不合法。", "location"));
            return;
        }
        if (building.length() > MAX_BUILDING_LEN) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                    errorBody("INVALID_FIELD",
                            "具体位置不能超过 " + MAX_BUILDING_LEN + " 个字符。",
                            "building"));
            return;
        }
        if (description.length() > MAX_DESCRIPTION_LEN) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                    errorBody("INVALID_FIELD",
                            "问题描述不能超过 " + MAX_DESCRIPTION_LEN + " 个字符。",
                            "description"));
            return;
        }
        if (contact.length() > MAX_CONTACT_LEN) {
            writeJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                    errorBody("INVALID_FIELD",
                            "联系方式不能超过 " + MAX_CONTACT_LEN + " 个字符。",
                            "contact"));
            return;
        }

        // 3) 模拟服务器忙 —— 503 SERVER_BUSY（字段校验通过后再判定）
        if (requestCounter.incrementAndGet() % BUSY_EVERY == 0) {
            resp.setHeader("Retry-After", "5");
            writeJson(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    errorBody("SERVER_BUSY",
                            "服务器忙，报修单暂时无法受理，请稍后重试。"));
            return;
        }

        // 4) 受理成功，生成工单号
        String ticketNo = generateTicketNo();

        // 实际项目中此处落库/派发工单；这里仅记录到容器日志
        log(String.format("报修受理成功 ticket=%s location=%s building=%s contact=%s",
                ticketNo, location, building, contact));

        String body = "{"
                + "\"code\":\"OK\","
                + "\"message\":\"报修成功，后勤师傅将尽快上门处理。\","
                + "\"ticketNo\":\"" + escape(ticketNo) + "\""
                + "}";
        writeJson(resp, HttpServletResponse.SC_OK, body);
    }

    /** 工单号格式：WX + yyyyMMddHHmmss + 3 位随机数，如 WX20260910153012483 */
    private String generateTicketNo() {
        return "WX" + LocalDateTime.now().format(TICKET_DATE_FORMAT)
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    private String errorBody(String code, String message) {
        return errorBody(code, message, null);
    }

    private String errorBody(String code, String message, String field) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"code\":\"").append(escape(code)).append("\",")
                .append("\"message\":\"").append(escape(message)).append("\"");
        if (field != null) {
            sb.append(",\"field\":\"").append(escape(field)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private void writeJson(HttpServletResponse resp, int status, String jsonBody)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        resp.setContentLength(bytes.length);
        try (OutputStream out = resp.getOutputStream()) {
            out.write(bytes);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** JSON 字符串最小转义，避免返回内容破坏 JSON 结构 */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
