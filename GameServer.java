import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class GameServer {
    static final int PORT;
    static final Path PUBLIC_DIR;
    static {
        String portStr = System.getenv("PORT");
        PORT = portStr != null ? Integer.parseInt(portStr) : 5000;
        String dir = System.getenv("PUBLIC_DIR");
        PUBLIC_DIR = dir != null ? Paths.get(dir) : Paths.get("public");
    }
    static final Map<String, Room> rooms = new ConcurrentHashMap<>();
    static final Set<String> usedCodes = ConcurrentHashMap.newKeySet();
    static final Map<String, Queue<SE>> eventQueues = new ConcurrentHashMap<>();
    static final Map<String, String> playerSids = new ConcurrentHashMap<>();
    static final Random RANDOM = new Random();
    static final List<String> TRUTH = new ArrayList<>();
    static {
        TRUTH.add("你最近一次撒谎是什么时候？说了什么？");
        TRUTH.add("你偷偷做过最丢人的一件事是什么？");
        TRUTH.add("你至今最后悔的一件事是什么？");
        TRUTH.add("你暗恋过谁？现在还有感觉吗？");
        TRUTH.add("你最害怕什么东西？为什么？");
        TRUTH.add("你做过最对不起朋友的事是什么？");
        TRUTH.add("如果明天是世界末日，你最想做什么？");
        TRUTH.add("你最大的缺点是什么？");
        TRUTH.add("你曾经出卖过朋友吗？");
        TRUTH.add("你做过最大胆的事是什么？");
        TRUTH.add("你曾经作弊过吗？");
        TRUTH.add("你做过最尴尬的事是什么？");
    }
    static final List<String> DARE = new ArrayList<>();
    static {
        DARE.add("模仿一种动物的叫声，持续10秒");
        DARE.add("原地转10圈然后走直线");
        DARE.add("用方言说一段绕口令");
        DARE.add("做10个俯卧撑");
        DARE.add("和左边的人深情对视30秒不笑");
        DARE.add("吃掉一勺醋");
        DARE.add("单脚站立唱一首歌");
        DARE.add("做20个高抬腿");
        DARE.add("学猩猩走路绕桌子一圈");
        DARE.add("把脸贴在玻璃上保持1分钟");
        DARE.add("倒立靠墙10秒钟");
        DARE.add("做一个你最丑的表情");
    }
    static final Map<String, Integer> cardIdx = new ConcurrentHashMap<>();

    static class Player { String id,name; int av; boolean ready,connected;
        Player(String i,String n,int a){id=i;name=n;av=a;ready=false;connected=true;} }
    static class GameData { int rn=0; Map<String,Integer> rolls=new ConcurrentHashMap<>();
        Set<String> rolled=ConcurrentHashMap.newKeySet();
        List<String> tieP=new CopyOnWriteArrayList<>();
        Map<String,Integer> tieR=new ConcurrentHashMap<>(); String wid,lid,td,ct="";
        Map<String,Boolean> votes=new ConcurrentHashMap<>(), sv=new ConcurrentHashMap<>();
        int ec=0; boolean tb=false; }
    static class Room { String code,hid; String phase="waiting",gs="idle";
        List<Player> players=new CopyOnWriteArrayList<>(); GameData g=new GameData(); }
    static class SE { String e,d; SE(String e,String d){this.e=e;this.d=d;} }

    static String genId(){return"p"+System.currentTimeMillis()+RANDOM.nextInt(10000);}
    static String genCode(){for(int i=0;i<100;i++){String c=String.format("%04d",RANDOM.nextInt(10000));
        if(!usedCodes.contains(c)){usedCodes.add(c);return c;}}return null;}
    static int d6(){return RANDOM.nextInt(6)+1;}
    static Map<String,Object> M(Object... kv){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    static String pickCard(String t){List<String>dk="truth".equals(t)?TRUTH:DARE;
        int idx=cardIdx.merge(t,0,(o,n)->(o+1)%dk.size());return dk.get(idx);}

    static void bcast(String rc,String ev,Object dt){Room r=rooms.get(rc);if(r==null)return;String j=toJson(dt);
        for(Player p:r.players)if(p.connected)eventQueues.computeIfAbsent(p.id,k->new ConcurrentLinkedQueue<>()).add(new SE(ev,j));}
    static void sendTo(String pid,String ev,Object dt){eventQueues.computeIfAbsent(pid,k->new ConcurrentLinkedQueue<>()).add(new SE(ev,toJson(dt)));}

    static void bstate(String rc){Room r=rooms.get(rc);if(r==null)return;
        List<Map<String,Object>>pl=r.players.stream().map(p->{Map<String,Object>m=new LinkedHashMap<>();
        m.put("id",p.id);m.put("name",p.name);m.put("avatarNumber",p.av);m.put("ready",p.ready);
        m.put("isHost",p.id.equals(r.hid));m.put("connected",p.connected);return m;}).collect(Collectors.toList());
        bcast(rc,"room_state",M("code",rc,"players",pl,"phase",r.phase));}

    static synchronized void advance(String rc) {
        Room room = rooms.get(rc);
        if (room == null) return;
        long active = room.players.stream().filter(p -> p.connected).count();
        if (active < 3) { bcast(rc,"game_message",M("message","至少需要3名玩家","type","error")); return; }
        GameData g = room.g;
        switch (room.gs) {
            case "idle" -> {
                room.gs="rolling"; g.rolls.clear(); g.rolled.clear(); g.rn++;
                g.ec=0; g.wid=null; g.lid=null; g.td=null; g.ct=""; g.votes.clear(); g.sv.clear();
                g.tb=false; g.tieP.clear(); g.tieR.clear();
                bcast(rc,"round_start",M("round",g.rn,"phase","rolling"));
            }
            case "rolling" -> {
                int mx=Collections.max(g.rolls.values()),mn=Collections.min(g.rolls.values());
                List<String> ws=new ArrayList<>(),ls=new ArrayList<>();
                for(var e:g.rolls.entrySet()){if(e.getValue()==mx)ws.add(e.getKey());if(e.getValue()==mn)ls.add(e.getKey());}
                if(ws.size()>1||ls.size()>1){Set<String>t=new HashSet<>();if(ws.size()>1)t.addAll(ws);if(ls.size()>1)t.addAll(ls);
                    g.tieP=new ArrayList<>(t);g.tieR.clear();g.tb=true;room.gs="tie_break";
                    bcast(rc,"dice_result",M("rolls",g.rolls,"tieBreak",true,"tiePlayers",g.tieP));
                    bcast(rc,"tie_break_start",M("tiePlayers",g.tieP));
                }else{finish(rc,ws.get(0),ls.get(0));}
            }
            case "tie_break" -> {
                var sorted=g.tieR.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).toList();
                int mx=Collections.max(g.rolls.values()),mn=Collections.min(g.rolls.values());
                var wc=g.rolls.entrySet().stream().filter(e->e.getValue()==mx).map(Map.Entry::getKey).toList();
                var lc=g.rolls.entrySet().stream().filter(e->e.getValue()==mn).map(Map.Entry::getKey).toList();
                String fw=wc.size()>1&&!sorted.isEmpty()?sorted.get(0).getKey():wc.get(0);
                String fl=lc.size()>1&&!sorted.isEmpty()?sorted.get(sorted.size()-1).getKey():lc.get(0);
                bcast(rc,"tie_break_result",M("tieRolls",g.tieR,"winner",fw,"loser",fl));
                finish(rc,fw,fl);
            }
            case "choosing" -> { bcast(rc,"truth_or_dare_chosen",M("choice",g.td,"loserId",g.lid)); room.gs="content_input";
                bcast(rc,"content_input_prompt",M("winnerId",g.wid,"choice",g.td)); }
            case "content_input" -> { room.gs="content_vote"; g.votes.clear();
                bcast(rc,"content_revealed",M("content",g.ct,"winnerId",g.wid,"loserId",g.lid));
                bcast(rc,"vote_content_prompt",M("content",g.ct,"excludePlayerId",g.lid)); }
            case "content_vote" -> { var voters=room.players.stream().filter(p->p.connected&&!p.id.equals(g.lid)).map(p->p.id).toList();
                boolean all=voters.stream().allMatch(pid->g.votes.getOrDefault(pid,false));
                if(all&&g.votes.size()>=voters.size()){room.gs="loser_decide";
                    bcast(rc,"content_approved",M()); bcast(rc,"loser_decide_prompt",M("loserId",g.lid,"content",g.ct));
                }else if(g.votes.size()>=voters.size()){bcast(rc,"content_rejected",M());room.gs="content_input";g.ct="";
                    bcast(rc,"content_input_prompt",M("winnerId",g.wid,"choice",g.td));}}
            case "executing" -> { room.gs="satisfaction_vote"; g.sv.clear();
                bcast(rc,"vote_satisfaction_prompt",M("excludePlayerId",g.lid,"executionCount",g.ec)); }
            case "satisfaction_vote" -> { var voters=room.players.stream().filter(p->p.connected&&!p.id.equals(g.lid)).map(p->p.id).toList();
                if(g.sv.size()<voters.size())break;
                long sat=g.sv.values().stream().filter(v->v).count();
                boolean passed=sat>=Math.ceil(voters.size()/2.0);
                if(passed){bcast(rc,"satisfaction_result",M("satisfied",(int)sat,"total",voters.size(),"passed",true));
                    bcast(rc,"round_ended",M("winnerId",g.wid,"loserId",g.lid,"message","🎉 本轮结束！"));
                    room.gs="idle";
                }else{g.ec++;if(g.ec>=3){bcast(rc,"satisfaction_result",M("satisfied",(int)sat,"total",voters.size(),"passed",true,"maxAttempts",true));
                    bcast(rc,"round_ended",M("winnerId",g.wid,"loserId",g.lid,"message","⏰ 已达最大执行次数"));
                    room.gs="idle";
                }else{bcast(rc,"satisfaction_result",M("satisfied",(int)sat,"total",voters.size(),"passed",false));
                    room.gs="executing";bcast(rc,"execute_prompt",M("loserId",g.lid,"content",g.ct,"executionCount",g.ec+1));}}}
        }
    }
    static void finish(String rc,String w,String l){Room r=rooms.get(rc);if(r==null)return;r.g.wid=w;r.g.lid=l;r.gs="choosing";
        bcast(rc,"dice_result",M("rolls",r.g.rolls,"winner",w,"loser",l,"tieBreak",false));
        bcast(rc,"choose_truth_or_dare",M("loserId",l));}

    @SuppressWarnings("unchecked")
    static Map<String,Object> parseJson(String json) {
        Map<String,Object> r=new LinkedHashMap<>(); json=json.trim();
        if(json.startsWith("{")&&json.endsWith("}")){json=json.substring(1,json.length()-1).trim();
            if(json.isEmpty())return r;
            for(String p:splitOut(json,',')){int ci=p.indexOf(':');if(ci<0)continue;
                String k=p.substring(0,ci).trim();String v=p.substring(ci+1).trim();
                if(k.startsWith("\""))k=k.substring(1,k.length()-1);r.put(k,parseVal(v));}}
        return r;}

    static List<String> splitOut(String s, char d) {
        List<String> r = new ArrayList<>();
        StringBuilder c = new StringBuilder();
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' && (i == 0 || s.charAt(i-1) != '\\'))
                inStr = !inStr;
            if (!inStr && ch == d) {
                r.add(c.toString().trim());
                c = new StringBuilder();
            } else {
                c.append(ch);
            }
        }
        r.add(c.toString().trim());
        return r;
    }

    static Object parseVal(String v){if(v.startsWith("\"")&&v.endsWith("\""))
        return v.substring(1,v.length()-1).replace("\\\"","\"").replace("\\n","\n");
        if("true".equals(v)||"false".equals(v))return Boolean.parseBoolean(v);
        if("null".equals(v))return null;
        try{return Integer.parseInt(v);}catch(NumberFormatException e){return v;}}
    @SuppressWarnings("unchecked")
    static String toJson(Object o){if(o==null)return"null";if(o instanceof String s)return"\""+esc(s)+"\"";
        if(o instanceof Number||o instanceof Boolean)return o.toString();
        if(o instanceof Map<?,?>m){StringBuilder sb=new StringBuilder("{");boolean f=true;
            for(Map.Entry<?,?>e:m.entrySet()){if(!f)sb.append(",");f=false;sb.append("\"").append(e.getKey()).append("\":").append(toJson(e.getValue()));}
            return sb.append("}").toString();}
        if(o instanceof List<?>l){StringBuilder sb=new StringBuilder("[");for(int i=0;i<l.size();i++){if(i>0)sb.append(",");sb.append(toJson(l.get(i)));}
            return sb.append("]").toString();}
        return"\""+esc(o.toString())+"\"";}
    static String esc(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", new StaticHandler());
        server.createContext("/events", new SSEHandler());
        server.createContext("/api", new ApiHandler());
        server.start();
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║    🎮 小游戏平台服务器已启动           ║");
        System.out.println("║  地址: http://0.0.0.0:"+PORT+"         ║");
        System.out.println("╚══════════════════════════════════════╝");
    }

    static class StaticHandler implements HttpHandler {
        static final Map<String,String> MIME = new HashMap<>();
        static {
            MIME.put("html","text/html; charset=utf-8");
            MIME.put("css","text/css; charset=utf-8");
            MIME.put("js","application/javascript; charset=utf-8");
            MIME.put("json","application/json");
            MIME.put("png","image/png"); MIME.put("jpg","image/jpeg");
            MIME.put("svg","image/svg+xml"); MIME.put("ico","image/x-icon");
        }
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            Path file = PUBLIC_DIR.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();
            if (!file.startsWith(PUBLIC_DIR.normalize()) || !Files.exists(file) || Files.isDirectory(file)) {
                byte[] msg = "404".getBytes(); ex.sendResponseHeaders(404, msg.length);
                ex.getResponseBody().write(msg); ex.getResponseBody().close(); return;
            }
            String ext = path.contains(".") ? path.substring(path.lastIndexOf('.')+1) : "";
            String mime = MIME.getOrDefault(ext.toLowerCase(), "application/octet-stream");
            byte[] content = Files.readAllBytes(file);
            ex.getResponseHeaders().add("Content-Type", mime);
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, content.length);
            ex.getResponseBody().write(content); ex.getResponseBody().close();
        }
    }

    static class SSEHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String query = ex.getRequestURI().getQuery();
            String pid = null;
            if (query != null) {
                for (String p : query.split("&")) {
                    String[] kv = p.split("=",2);
                    if (kv.length==2 && kv[0].equals("player_id"))
                        pid = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                }
            }
            if (pid == null || pid.isEmpty()) { ex.sendResponseHeaders(400,-1); return; }
            ex.getResponseHeaders().add("Content-Type","text/event-stream; charset=utf-8");
            ex.getResponseHeaders().add("Cache-Control","no-cache");
            ex.getResponseHeaders().add("Connection","keep-alive");
            ex.getResponseHeaders().add("Access-Control-Allow-Origin","*");
            ex.sendResponseHeaders(200, 0);
            Queue<SE> q = eventQueues.computeIfAbsent(pid, k -> new ConcurrentLinkedQueue<>());
            OutputStream os = ex.getResponseBody();
            long lastBeat = System.currentTimeMillis();
            try {
                os.write(("event: connected\ndata: {}\n\n").getBytes(StandardCharsets.UTF_8)); os.flush();
                while (true) {
                    SE ev = q.poll();
                    if (ev != null) {
                        String msg = "event: "+ev.e+"\ndata: "+ev.d+"\n\n";
                        os.write(msg.getBytes(StandardCharsets.UTF_8)); os.flush();
                        lastBeat = System.currentTimeMillis();
                    } else {
                        long now = System.currentTimeMillis();
                        if (now - lastBeat > 5000) {
                            os.write(":\n\n".getBytes(StandardCharsets.UTF_8)); os.flush();
                            lastBeat = now;
                        }
                        try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                    }
                }
            } catch (IOException e) { /* disconnect */ }
            finally { eventQueues.remove(pid); try { os.close(); } catch (IOException e) {} }
        }
    }

    static class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin","*");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods","POST,GET,OPTIONS");
            ex.getResponseHeaders().add("Access-Control-Allow-Headers","Content-Type");
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(204,-1); return; }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                byte[] err = toJson(M("error","Method not allowed")).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type","application/json");
                ex.sendResponseHeaders(405, err.length); ex.getResponseBody().write(err); ex.getResponseBody().close(); return;
            }
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String,Object> data = body.isEmpty() ? new HashMap<>() : parseJson(body);
            try {
                switch (path) {
                    case "/api/create_room" -> handleCreateRoom(ex, data);
                    case "/api/join_room" -> handleJoinRoom(ex, data);
                    case "/api/toggle_ready" -> handleToggleReady(ex, data);
                    case "/api/start_game" -> handleStartGame(ex, data);
                    case "/api/roll_dice" -> handleRollDice(ex, data);
                    case "/api/choose" -> handleChoose(ex, data);
                    case "/api/submit_content" -> handleSubmitContent(ex, data);
                    case "/api/vote_content" -> handleVoteContent(ex, data);
                    case "/api/loser_accept" -> handleLoserAccept(ex, data);
                    case "/api/loser_reject" -> handleLoserReject(ex, data);
                    case "/api/execution_done" -> handleExecDone(ex, data);
                    case "/api/vote_satisfaction" -> handleVoteSat(ex, data);
                    case "/api/next_round" -> handleNextRound(ex, data);
                    case "/api/leave_room" -> handleLeaveRoom(ex, data);
                    default -> sendJson(ex,404,M("error","Not found"));
                }
            } catch (Exception e) { e.printStackTrace(); sendJson(ex,500,M("error",e.getMessage())); }
        }
        void handleCreateRoom(HttpExchange ex, Map<String,Object> data) throws IOException {
                String name = (String)data.getOrDefault("name","玩家"+RANDOM.nextInt(900));
                String code = genCode();
                if(code==null){sendJson(ex,500,M("error","无法创建"));return;}
                String pid=genId(); Room r=new Room(); r.code=code; r.hid=pid;
                r.players.add(new Player(pid,name,1)); rooms.put(code,r); playerSids.put(pid,code);
                sendJson(ex,200,M("code",code,"playerId",pid)); bstate(code);}
            void handleJoinRoom(HttpExchange ex, Map<String,Object> data) throws IOException {
                String code=(String)data.get("code"),name=(String)data.getOrDefault("name","玩家"+RANDOM.nextInt(900));
                if(code==null){sendJson(ex,400,M("error","缺少房间号"));return;}
                Room r=rooms.get(code);
                if(r==null){sendJson(ex,404,M("error","房间不存在"));return;}
                if(!"waiting".equals(r.phase)){sendJson(ex,400,M("error","游戏已开始"));return;}
                if(r.players.size()>=8){sendJson(ex,400,M("error","房间已满"));return;}
                Set<String>ns=r.players.stream().map(p->p.name).collect(Collectors.toSet());
                if(ns.contains(name))name+=RANDOM.nextInt(100);
                Set<Integer>nums=r.players.stream().map(p->p.av).collect(Collectors.toSet());
                int an=1;while(nums.contains(an))an++;String pid=genId();
                r.players.add(new Player(pid,name,an)); playerSids.put(pid,code);
                sendJson(ex,200,M("playerId",pid,"code",code));bstate(code);}
            void handleToggleReady(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId");if(pid==null){sendJson(ex,400,M("error","缺少playerId"));return;}
                String rc=playerSids.get(pid);if(rc==null){sendJson(ex,400,M("error","未在房间"));return;}
                Room r=rooms.get(rc);if(r==null||!"waiting".equals(r.phase)){sendJson(ex,400,M("error","状态错误"));return;}
                for(Player p:r.players)if(p.id.equals(pid)){p.ready=!p.ready;break;}
                bstate(rc);sendJson(ex,200,M("ok",true));
                long act=r.players.stream().filter(p->p.connected).count();
                boolean all=r.players.stream().filter(p->p.connected).allMatch(p->p.ready);
                if(all&&act>=3)sendTo(r.hid,"all_ready",M());}
            void handleStartGame(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId");if(pid==null){sendJson(ex,400,M("error","缺少playerId"));return;}
                String rc=playerSids.get(pid);Room r=rooms.get(rc);
                if(r==null||!r.hid.equals(pid)){sendJson(ex,400,M("error","禁止"));return;}
                long act=r.players.stream().filter(p->p.connected).count();
                boolean all=r.players.stream().filter(p->p.connected).allMatch(p->p.ready);
                if(act<3||!all){sendJson(ex,400,M("error","条件不足"));return;}
                r.phase="playing";r.gs="idle";bstate(rc);bcast(rc,"game_starting",M());
                sendJson(ex,200,M("ok",true));
                try{Thread.sleep(1000);}catch(InterruptedException ignored){}
                advance(rc);}
            void handleRollDice(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId");if(pid==null){sendJson(ex,400,M("error","缺少playerId"));return;}
                String rc=playerSids.get(pid);if(rc==null){sendJson(ex,400,M("error","未在房间"));return;}
                Room r=rooms.get(rc);if(r==null){sendJson(ex,400,M("error","房间不存在"));return;}
                GameData g=r.g;
                if("rolling".equals(r.gs)){if(g.rolled.contains(pid)){sendJson(ex,400,M("error","已掷过"));return;}
                    int v=d6();g.rolls.put(pid,v);g.rolled.add(pid);
                    sendTo(pid,"dice_rolled",M("value",v));bcast(rc,"player_rolled",M("playerId",pid));
                    sendJson(ex,200,M("ok",true));
                    long act=r.players.stream().filter(p->p.connected).count();
                    if(g.rolled.size()>=act)advance(rc);
                }else if("tie_break".equals(r.gs)){if(!g.tieP.contains(pid)||g.tieR.containsKey(pid)){sendJson(ex,400,M("error","不可重掷"));return;}
                    int v=d6();g.tieR.put(pid,v);sendTo(pid,"tie_dice_rolled",M("value",v));
                    bcast(rc,"player_tie_rolled",M("playerId",pid));sendJson(ex,200,M("ok",true));
                    if(g.tieR.size()>=g.tieP.size())advance(rc);
                }else sendJson(ex,400,M("error","当前不可掷骰"));}
            void handleChoose(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId"),ch=(String)data.get("choice");
                if(pid==null||ch==null){sendJson(ex,400,M("error","参数不足"));return;}
                String rc=playerSids.get(pid);if(rc==null){sendJson(ex,400,M("error","未在房间"));return;}
                Room r=rooms.get(rc);
                if(r==null||!"choosing".equals(r.gs)||!pid.equals(r.g.lid)||(!ch.equals("truth")&&!ch.equals("dare"))){sendJson(ex,400,M("error","不可选择"));return;}
                r.g.td=ch;sendJson(ex,200,M("ok",true));advance(rc);}
            void handleSubmitContent(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId"),ct=(String)data.get("content");
                if(pid==null||ct==null||ct.trim().isEmpty()){sendJson(ex,400,M("error","内容不能为空"));return;}
                String rc=playerSids.get(pid);if(rc==null){sendJson(ex,400,M("error","未在房间"));return;}
                Room r=rooms.get(rc);
                if(r==null||!"content_input".equals(r.gs)||!pid.equals(r.g.wid)){sendJson(ex,400,M("error","不可提交"));return;}
                r.g.ct=ct.trim();sendJson(ex,200,M("ok",true));advance(rc);}
            void handleVoteContent(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId");Boolean ag=(Boolean)data.get("agree");
                if(pid==null||ag==null){sendJson(ex,400,M("error","参数不足"));return;}
                String rc=playerSids.get(pid);if(rc==null){sendJson(ex,400,M("error","未在房间"));return;}
                Room r=rooms.get(rc);
                if(r==null||!"content_vote".equals(r.gs)||pid.equals(r.g.lid)){sendJson(ex,400,M("error","不可投票"));return;}
                r.g.votes.put(pid,ag);bcast(rc,"player_voted_content",M("playerId",pid));
                sendJson(ex,200,M("ok",true));
                var voters=r.players.stream().filter(p->p.connected&&!p.id.equals(r.g.lid)).map(p->p.id).toList();
                if(r.g.votes.size()>=voters.size())advance(rc);}
            void handleLoserAccept(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId");if(pid==null){sendJson(ex,400,M("error","缺少playerId"));return;}
                String rc=playerSids.get(pid);if(rc==null){sendJson(ex,400,M("error","未在房间"));return;}
                Room r=rooms.get(rc);
                if(r==null||!"loser_decide".equals(r.gs)||!pid.equals(r.g.lid)){sendJson(ex,400,M("error","不可操作"));return;}
                r.gs="executing";r.g.ec=0;bcast(rc,"loser_accepted",M());
                bcast(rc,"execute_prompt",M("loserId",pid,"content",r.g.ct,"executionCount",1));sendJson(ex,200,M("ok",true));}
            void handleLoserReject(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId");if(pid==null){sendJson(ex,400,M("error","缺少playerId"));return;}
                String rc=playerSids.get(pid);if(rc==null){sendJson(ex,400,M("error","未在房间"));return;}
                Room r=rooms.get(rc);
                if(r==null||!"loser_decide".equals(r.gs)||!pid.equals(r.g.lid)){sendJson(ex,400,M("error","不可操作"));return;}
                String card=pickCard(r.g.td);r.g.ct=card;r.gs="executing";r.g.ec=0;
                bcast(rc,"card_drawn",M("card",card,"type",r.g.td));
                bcast(rc,"execute_prompt",M("loserId",pid,"content",card,"executionCount",1));sendJson(ex,200,M("ok",true));}
            void handleExecDone(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId");if(pid==null){sendJson(ex,400,M("error","缺少playerId"));return;}
                String rc=playerSids.get(pid);if(rc==null){sendJson(ex,400,M("error","未在房间"));return;}
                Room r=rooms.get(rc);
                if(r==null||!"executing".equals(r.gs)||!pid.equals(r.g.lid)){sendJson(ex,400,M("error","不可操作"));return;}
                sendJson(ex,200,M("ok",true));advance(rc);}
            void handleVoteSat(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId");Boolean sat=(Boolean)data.get("satisfied");
                if(pid==null||sat==null){sendJson(ex,400,M("error","参数不足"));return;}
                String rc=playerSids.get(pid);if(rc==null){sendJson(ex,400,M("error","未在房间"));return;}
                Room r=rooms.get(rc);
                if(r==null||!"satisfaction_vote".equals(r.gs)||pid.equals(r.g.lid)){sendJson(ex,400,M("error","不可投票"));return;}
                r.g.sv.put(pid,sat);bcast(rc,"player_voted_satisfaction",M("playerId",pid));
                sendJson(ex,200,M("ok",true));
                var voters=r.players.stream().filter(p->p.connected&&!p.id.equals(r.g.lid)).map(p->p.id).toList();
                if(r.g.sv.size()>=voters.size())advance(rc);}
            void handleNextRound(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId");if(pid==null){sendJson(ex,400,M("error","缺少playerId"));return;}
                String rc=playerSids.get(pid);if(rc==null){sendJson(ex,400,M("error","未在房间"));return;}
                Room r=rooms.get(rc);
                if(r==null||!"idle".equals(r.gs)||!r.hid.equals(pid)){sendJson(ex,400,M("error","不可操作"));return;}
                sendJson(ex,200,M("ok",true));advance(rc);}
            void handleLeaveRoom(HttpExchange ex, Map<String,Object> data) throws IOException {
                String pid=(String)data.get("playerId");if(pid==null){sendJson(ex,400,M("error","缺少playerId"));return;}
                String rc=playerSids.remove(pid);
                if(rc!=null){Room r=rooms.get(rc);if(r!=null){r.players.removeIf(p->p.id.equals(pid));
                    if(r.players.isEmpty()){usedCodes.remove(rc);rooms.remove(rc);}
                    else{if(r.hid.equals(pid))r.players.stream().filter(p->p.connected).findFirst().ifPresent(p->r.hid=p.id);
                    bstate(rc);}}eventQueues.remove(pid);}
                sendJson(ex,200,M("ok",true));}

        void sendJson(HttpExchange ex, int status, Object data) throws IOException {
            byte[] json = toJson(data).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type","application/json; charset=utf-8");
            ex.sendResponseHeaders(status, json.length);
            ex.getResponseBody().write(json); ex.getResponseBody().close();
        }
    }
}