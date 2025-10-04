package org.example;

import com.fastcgi.FCGIInterface;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HitServerTcp {

    private static final List<Result> HISTORY = new ArrayList<>();
    private static final double[] ALLOWED_X = {-3, -2, -1, 0, 1, 2, 3, 4, 5};
    private static final double[] ALLOWED_R = {1, 1.5, 2, 2.5, 3};

    public static void main(String[] args) {
        FCGIInterface fcgi = new FCGIInterface();

        while (fcgi.FCGIaccept() >= 0) {
            long startNs = System.nanoTime();

            try {
                String method = System.getProperty("REQUEST_METHOD");
                String query  = System.getProperty("QUERY_STRING");
                String body   = readStdin();

                Map<String,String> p = new LinkedHashMap<>();
                p.putAll(parseForm(query));
                if ("POST".equalsIgnoreCase(method)) {
                    p.putAll(parseForm(body));
                }

                if ("1".equals(p.get("history")) && !p.containsKey("x") && !p.containsKey("y") && !p.containsKey("r")) {
                    String json = "{\"history\":" + jsonHistory() + "}";
                    write200(json);
                    continue;
                }

                Validation v = validate(p);
                if (!v.ok) {
                    String json = "{\"ok\":false,\"error\":" + jsonStr(v.message) + ",\"history\":" + jsonHistory() + "}";
                    write400(json);
                    continue;
                }

                boolean hit = hit(v.x, v.y, v.r);
                Result r = new Result(v.x, v.y, v.r, hit, LocalDateTime.now().toString(), System.nanoTime() - startNs);
                HISTORY.add(r);

                String json = "{\"ok\":true,\"result\":" + jsonResult(r) + ",\"history\":" + jsonHistory() + "}";
                write200(json);

            } catch (Throwable t) {
                String json = "{\"ok\":false,\"error\":\"internal error\",\"history\":" + jsonHistory() + "}";
                write400(json);
            }
        }
    }


    private static String readStdin() throws IOException {
        String cl = System.getProperty("CONTENT_LENGTH");
        if (cl == null || cl.isEmpty()) return "";
        int len;
        try { len = Integer.parseInt(cl); } catch (Exception e) { return ""; }
        if (len <= 0) return "";
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int rd = System.in.read(buf, off, len - off);
            if (rd < 0) break;
            off += rd;
        }
        return new String(buf, 0, off, StandardCharsets.UTF_8);
    }

    private static Map<String,String> parseForm(String s) {
        Map<String,String> m = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return m;
        String[] pairs = s.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            String k = urlDecode(kv[0]);
            String v = (kv.length > 1) ? urlDecode(kv[1]) : "";
            m.put(k, v);
        }
        return m;
    }
    private static String urlDecode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8.name()); }
        catch (Exception e) { return s; }
    }


    private static class Validation { boolean ok; String message; double x,y,r;
        Validation(String m){ ok=false; message=m; }
        Validation(double x,double y,double r){ ok=true; this.x=x; this.y=y; this.r=r; }
    }
    private static Validation validate(Map<String,String> p) {
        try {
            if (!p.containsKey("x") || !p.containsKey("y") || !p.containsKey("r"))
                return new Validation("Надо и x, и y, и r.");
            double x = parseNum(p.get("x"));
            double y = parseNum(p.get("y"));
            double r = parseNum(p.get("r"));
            if (!allowedX(x)) return new Validation("x должен быть в: [-3,-2,-1,0,1,2,3,4,5]");
            if (y < -3 || y > 5) return new Validation("y должен быть в: [-3, 5]");
            if (!allowedR(r)) return new Validation("r должен быть в: [1,1.5,2,2.5,3]");
            return new Validation(x,y,r);
        } catch (NumberFormatException e) {
            return new Validation("x, y и r должны быть числами.");
        }
    }
    private static boolean allowedX(double x) {
        for (double v : ALLOWED_X) if (Double.compare(v, x) == 0) return true;
        return false;
    }
    private static boolean allowedR(double r) {
        for (double v : ALLOWED_R) if (Double.compare(v, r) == 0) return true;
        return false;
    }
    private static double parseNum(String s) {
        return Double.parseDouble(s.trim().replace(',', '.'));
    }

    private static boolean hit(double x, double y, double r) {
        if (x < -r || x > 0 || y < 0 || y > r) return false;
        double maxY = (x >= -r / 2) ? (r / 2 - x) : (r / 2);
        return y <= maxY;
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\"";
    }
    private static String jsonResult(Result r) {
        return "{" +
                "\"x\":" + r.x + "," +
                "\"y\":" + r.y + "," +
                "\"r\":" + r.r + "," +
                "\"hit\":" + r.hit + "," +
                "\"time\":" + jsonStr(r.time) + "," +
                "\"execTimeNs\":" + r.execTimeNs +
                "}";
    }
    private static String jsonHistory() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < HISTORY.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonResult(HISTORY.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    private static void write200(String json) {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        System.out.print("HTTP/1.1 200 OK\r\n");
        System.out.print("Content-Type: application/json\r\n");
        System.out.print("Content-Length: " + b.length + "\r\n\r\n");
        System.out.write(b, 0, b.length);
        System.out.flush();
    }
    private static void write400(String json) {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        System.out.print("HTTP/1.1 400 Bad Request\r\n");
        System.out.print("Content-Type: application/json\r\n");
        System.out.print("Content-Length: " + b.length + "\r\n\r\n");
        System.out.write(b, 0, b.length);
        System.out.flush();
    }

    private static class Result {
        double x,y,r; boolean hit; String time; long execTimeNs;
        Result(double x,double y,double r,boolean hit,String time,long execTimeNs){
            this.x=x; this.y=y; this.r=r; this.hit=hit; this.time=time; this.execTimeNs=execTimeNs;
        }
    }
}