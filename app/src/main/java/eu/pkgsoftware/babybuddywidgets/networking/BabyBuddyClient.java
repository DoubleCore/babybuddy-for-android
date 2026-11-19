package eu.pkgsoftware.babybuddywidgets.networking;

import android.os.Handler;
import android.os.Looper;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import eu.pkgsoftware.babybuddywidgets.CredStore;
import eu.pkgsoftware.babybuddywidgets.local.LocalBabyBuddyRepository;
import eu.pkgsoftware.babybuddywidgets.local.ModelValidators;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.Client;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry;

/**
 * Local standalone adapter preserving the BabyBuddyClient callback interface while
 * executing 100% against {@link LocalBabyBuddyRepository} with zero network I/O.
 */
public class BabyBuddyClient extends StreamReader {
    public static final String DATE_FORMAT_STRING = "yyyy-MM-dd'T'HH:mm:ssX";

    public static class ACTIVITIES {
        public static final String SLEEP = "sleep";
        public static final String TUMMY_TIME = "tummy-times";
        public static final String FEEDING = "feedings";
        public static final String PUMPING = "pumping";

        public static final String[] ALL = new String[4];

        static {
            ALL[0] = FEEDING;
            ALL[1] = SLEEP;
            ALL[2] = TUMMY_TIME;
            ALL[3] = PUMPING;
        }

        public static int index(String s) {
            for (int i = 0; i < ALL.length; i++) {
                if (Objects.equals(ALL[i], s)) {
                    return i;
                }
            }
            return -1;
        }
    }

    public static class EVENTS {
        public static final String CHANGE = "changes";
        public static final String NOTE = "notes";
        public static final String TEMPERATURE = "temperature";
        public static final String WEIGHT = "weight";
        public static final String BMI = "bmi";
        public static final String HEIGHT = "height";
        public static final String HEAD_CIRCUMFERENCE = "head-circumference";
        public static final String MEDICATION = "medications";

        public static final String[] ALL = new String[8];

        static {
            ALL[0] = CHANGE;
            ALL[1] = NOTE;
            ALL[2] = TEMPERATURE;
            ALL[3] = WEIGHT;
            ALL[4] = BMI;
            ALL[5] = HEIGHT;
            ALL[6] = HEAD_CIRCUMFERENCE;
            ALL[7] = MEDICATION;
        }

        public static int index(String s) {
            for (int i = 0; i < ALL.length; i++) {
                if (Objects.equals(ALL[i], s)) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static Date parseNullOrDate(JSONObject o, String field) throws JSONException, ParseException {
        if (o.isNull(field)) {
            return null;
        }
        final SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_STRING, Locale.ENGLISH);
        String strDate = o.getString(field);
        strDate = strDate.replaceAll("\\.[0-9]+([+-Z])", "$1");
        strDate = strDate.replaceAll("Z$", "+00:00");
        return sdf.parse(strDate);
    }

    private static String dateToString(Date date) {
        if (date == null) {
            return null;
        }
        final SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_STRING, Locale.ENGLISH);
        return sdf.format(date);
    }

    private static String dateToQueryString(Date date) {
        if (date == null) {
            return null;
        }
        final SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_STRING, Locale.ENGLISH);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date);
    }

    public static class QueryValues {
        public HashMap<String, String> queryValues = new HashMap<>();

        public QueryValues add(String name, String value) {
            queryValues.put(name, value);
            return this;
        }

        public QueryValues add(String name, int value) {
            return this.add(name, "" + value);
        }

        public QueryValues add(String name, Date value) {
            return this.add(name, dateToQueryString(value));
        }

        public String toQueryString() {
            StringBuilder result = new StringBuilder();
            for (Map.Entry<String, String> e : queryValues.entrySet()) {
                if (result.length() > 0) {
                    result.append("&");
                }
                result.append(e.getKey());
                result.append("=");
                result.append(urlencode(e.getValue()));
            }
            return result.toString();
        }

        public JSONObject toJsonObject() {
            return new JSONObject(queryValues);
        }
    }

    public static class Child {
        public int id;
        public String slug;
        public String first_name;
        public String last_name;
        public String birth_date;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Child child = (Child) o;
            return id == child.id &&
                Objects.equals(slug, child.slug) &&
                Objects.equals(first_name, child.first_name) &&
                Objects.equals(last_name, child.last_name) &&
                Objects.equals(birth_date, child.birth_date);
        }

        public static Child fromModel(eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child m) {
            Child c = new Child();
            c.id = m.getId();
            c.slug = m.getSlug();
            c.first_name = m.getFirstName();
            c.last_name = m.getLastName();
            c.birth_date = ModelValidators.INSTANCE.formatIsoDate(m.getBirthData(), TimeZone.getDefault());
            return c;
        }

        public static Child fromJSON(String s) throws JSONException {
            return fromJSON(new JSONObject(s));
        }

        public static Child fromJSON(JSONObject obj) throws JSONException {
            Child c = new Child();
            c.id = obj.getInt("id");
            c.slug = obj.getString("slug");
            c.first_name = obj.getString("first_name");
            c.last_name = obj.optString("last_name", "");
            c.birth_date = obj.getString("birth_date");
            return c;
        }

        public JSONObject toJSON() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("slug", slug);
                o.put("first_name", first_name);
                o.put("last_name", last_name);
                o.put("birth_date", birth_date);
            } catch (JSONException e) {
                throw new RuntimeException("ERROR should not happen");
            }
            return o;
        }
    }

    public static class Timer {
        public int id;
        public Integer child_id;
        public String name;
        public Date start;
        public Date end;
        public boolean active;
        public int user_id;

        public @NonNull String readableName() {
            if (name != null) {
                return name;
            }
            return "Quick timer #" + id;
        }

        public Date computeCurrentServerEndTime(BabyBuddyClient client) {
            if (end != null) {
                return end;
            }
            return new Date();
        }

        public static Timer fromModel(eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Timer m) {
            Timer t = new Timer();
            t.id = m.getId();
            t.child_id = m.getChildId() > 0 ? m.getChildId() : null;
            t.name = m.getName();
            t.start = m.getStart();
            t.end = null;
            t.active = true;
            t.user_id = m.getUserId();
            return t;
        }

        public static Timer fromJSON(JSONObject obj) throws JSONException, ParseException {
            Timer t = new Timer();
            t.id = obj.getInt("id");
            t.child_id = obj.isNull("child") ? null : obj.getInt("child");
            t.name = obj.isNull("name") ? null : obj.getString("name");
            t.start = parseNullOrDate(obj, "start");
            t.end = parseNullOrDate(obj, "end");
            t.active = obj.optBoolean("active", true);
            t.user_id = obj.optInt("user", 1);
            return t;
        }

        public JSONObject toJSON() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("child", child_id);
                o.put("name", name);
                o.put("start", dateToString(start));
                o.put("end", dateToString(end));
                o.put("active", active);
                o.put("user", user_id);
            } catch (JSONException e) {
                throw new RuntimeException("ERROR should not happen");
            }
            return o;
        }

        @Override
        public String toString() {
            return "Timer{" +
                "id=" + id +
                ", child_id=" + child_id +
                ", name='" + name + '\'' +
                ", start=" + start +
                ", end=" + end +
                ", active=" + active +
                ", user_id=" + user_id +
                '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Timer timer = (Timer) o;
            return id == timer.id &&
                active == timer.active &&
                user_id == timer.user_id &&
                Objects.equals(child_id, timer.child_id) &&
                Objects.equals(name, timer.name) &&
                Objects.equals(start, timer.start) &&
                Objects.equals(end, timer.end);
        }

        public Timer clone() {
            Timer result = new Timer();
            result.id = id;
            result.child_id = child_id;
            result.name = name;
            result.start = start;
            result.end = end;
            result.active = active;
            result.user_id = user_id;
            return result;
        }
    }

    public static class TimeEntry {
        public String type;
        public int typeId;
        public Date start;
        public Date end;
        public String notes;

        public static TimeEntry fromJsonObject(JSONObject json, String type) throws JSONException, ParseException {
            String notes = null;
            if (json.has("milestone")) {
                notes = json.optString("milestone", "");
            }
            if (json.has("notes") && (notes == null || notes.isEmpty())) {
                notes = json.optString("notes", "");
            }
            return new TimeEntry(
                type,
                json.getInt("id"),
                parseNullOrDate(json, "start"),
                parseNullOrDate(json, "end"),
                notes == null ? "" : notes
            );
        }

        public TimeEntry(String type, int typeId, Date start, Date end, String notes) {
            this.type = type;
            this.typeId = typeId;
            this.start = start;
            this.end = end;
            this.notes = notes;
        }

        @Override
        public String toString() {
            return "TimeEntry{" +
                "type='" + type + '\'' +
                ", typeId=" + typeId +
                ", start=" + start +
                ", end=" + end +
                ", notes='" + notes + '\'' +
                '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TimeEntry timeEntry = (TimeEntry) o;
            return typeId == timeEntry.typeId &&
                Objects.equals(type, timeEntry.type) &&
                Objects.equals(start, timeEntry.start) &&
                Objects.equals(end, timeEntry.end) &&
                Objects.equals(notes, timeEntry.notes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, typeId, start, end, notes);
        }
    }

    public class GenericSubsetResponseHeader<T> {
        public final int offset;
        public final int totalCount;
        public final T payload;

        public GenericSubsetResponseHeader(int offset, int totalCount, T payload) {
            this.offset = offset;
            this.totalCount = totalCount;
            this.payload = payload;
        }
    }

    public class GenericListSubsetResponse<LT> {
        public final int offset;
        public final int totalCount;
        public final LT[] list;

        public GenericListSubsetResponse(int offset, int totalCount, LT[] list) {
            this.offset = offset;
            this.totalCount = totalCount;
            this.list = list;
        }
    }

    public interface RequestCallback<R> {
        void error(@NotNull Exception error);
        void response(R response);
    }

    private final Handler syncMessage;
    private final CredStore credStore;
    public final LocalBabyBuddyRepository repository;
    public final Client v2client;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    public BabyBuddyClient(Looper mainLoop, CredStore credStore, LocalBabyBuddyRepository repository) {
        this.credStore = credStore;
        this.syncMessage = new Handler(mainLoop);
        this.repository = repository;
        this.v2client = new Client(repository);
    }

    private <R> void runAsync(CallableTask<R> task, RequestCallback<R> callback) {
        ioExecutor.execute(() -> {
            try {
                R res = task.call();
                syncMessage.post(() -> callback.response(res));
            } catch (Exception e) {
                syncMessage.post(() -> callback.error(e));
            }
        });
    }

    private interface CallableTask<R> {
        R call() throws Exception;
    }

    public void listChildren(RequestCallback<Child[]> callback) {
        listChildren(new QueryValues(), callback);
    }

    public void listChildren(@NotNull QueryValues queryValues, RequestCallback<Child[]> callback) {
        runAsync(() -> {
            Integer idFilter = null;
            if (queryValues.queryValues.containsKey("id")) {
                try {
                    idFilter = Integer.parseInt(Objects.requireNonNull(queryValues.queryValues.get("id")));
                } catch (NumberFormatException ignored) {
                }
            }
            String slugFilter = queryValues.queryValues.get("slug");
            List<eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child> models =
                repository.listChildren(idFilter, slugFilter);
            Child[] out = new Child[models.size()];
            for (int i = 0; i < models.size(); i++) {
                out[i] = Child.fromModel(models.get(i));
            }
            return out;
        }, callback);
    }

    public void checkChildExists(int child_id, RequestCallback<Boolean> callback) {
        runAsync(() -> repository.getChildById(child_id) != null, callback);
    }

    public void createTimer(int child_id, String name, RequestCallback<Timer> callback) {
        runAsync(() -> {
            eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Timer created =
                repository.createTimer(child_id > 0 ? child_id : null, name, System.currentTimeMillis());
            return Timer.fromModel(created);
        }, callback);
    }

    public void listTimers(RequestCallback<Timer[]> callback) {
        listTimers(null, callback);
    }

    public void listTimers(Integer child_id, RequestCallback<Timer[]> callback) {
        runAsync(() -> {
            List<eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Timer> models =
                repository.listTimers(child_id);
            List<Timer> result = new ArrayList<>(models.size());
            for (eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Timer m : models) {
                result.add(Timer.fromModel(m));
            }
            result.sort(Comparator.comparingInt(t -> t.id));
            return result.toArray(new Timer[0]);
        }, callback);
    }

    public void deleteTimer(int timer_id, RequestCallback<Boolean> callback) {
        runAsync(() -> repository.deleteTimer(timer_id), callback);
    }

    private static String urlencode(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    public void restartTimer(int timer_id, RequestCallback<Timer> callback) {
        runAsync(() -> {
            eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Timer restarted =
                repository.restartTimer(timer_id, System.currentTimeMillis());
            return Timer.fromModel(restarted);
        }, callback);
    }

    public long getServerDateOffsetMillis() {
        return 0L;
    }

    public void listGeneric(
        String activity,
        int offset,
        QueryValues queryValues,
        RequestCallback<GenericSubsetResponseHeader<JSONArray>> callback
    ) {
        runAsync(() -> {
            QueryValues qv = queryValues != null ? queryValues : new QueryValues();
            Integer childId = null;
            if (qv.queryValues.containsKey("child")) {
                try {
                    childId = Integer.parseInt(Objects.requireNonNull(qv.queryValues.get("child")));
                } catch (NumberFormatException ignored) {
                }
            }
            long startMax = Long.MAX_VALUE;
            long endMin = Long.MIN_VALUE;
            if (qv.queryValues.containsKey("start_max")) {
                Date d = parseIsoSafe(qv.queryValues.get("start_max"));
                if (d != null) startMax = d.getTime();
            }
            if (qv.queryValues.containsKey("end_min")) {
                Date d = parseIsoSafe(qv.queryValues.get("end_min"));
                if (d != null) endMin = d.getTime();
            }

            List<eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry> conflicts =
                childId != null ? repository.findIntersectingTimedEntries(activity, childId, endMin, startMax, 0)
                    : new ArrayList<>();
            JSONArray arr = new JSONArray();
            for (eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry item : conflicts) {
                JSONObject o = new JSONObject();
                o.put("id", item.getId());
                o.put("start", dateToString(item.getStart()));
                o.put("end", dateToString(item.getEnd()));
                o.put("notes", item.getNotes());
                arr.put(o);
            }
            return new GenericSubsetResponseHeader<>(offset, arr.length(), arr);
        }, callback);
    }

    public void updateTimelineEntry(
        @NotNull TimeEntry entry,
        @NotNull QueryValues values,
        @NotNull RequestCallback<TimeEntry> callback
    ) {
        runAsync(() -> {
            eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry existing =
                repository.getEntryByTypeAndId(entry.type, entry.typeId);
            if (existing == null) {
                return entry;
            }
            Date newStart = values.queryValues.containsKey("start")
                ? parseIsoSafe(values.queryValues.get("start"))
                : existing.getStart();
            Date newEnd = values.queryValues.containsKey("end")
                ? parseIsoSafe(values.queryValues.get("end"))
                : existing.getEnd();
            if (newStart == null) newStart = existing.getStart();
            if (newEnd == null) newEnd = existing.getEnd();

            eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry updatedModel = existing;
            if (existing instanceof FeedingEntry) {
                FeedingEntry f = (FeedingEntry) existing;
                updatedModel = repository.updateEntry(
                    new FeedingEntry(f.getId(), f.getChildId(), newStart, newEnd, f.getNotes(), f.getFeedingType(), f.getFeedingMethod(), f.getAmount(), f.getTags())
                );
            } else if (existing instanceof SleepEntry) {
                SleepEntry s = (SleepEntry) existing;
                updatedModel = repository.updateEntry(
                    new SleepEntry(s.getId(), s.getChildId(), newStart, newEnd, s.getNotes(), s.getNap(), s.getTags())
                );
            } else if (existing instanceof TummyTimeEntry) {
                TummyTimeEntry t = (TummyTimeEntry) existing;
                updatedModel = repository.updateEntry(
                    new TummyTimeEntry(t.getId(), t.getChildId(), newStart, newEnd, t.getMilestone(), t.getExtraNotes(), t.getTags())
                );
            } else if (existing instanceof PumpingEntry) {
                PumpingEntry p = (PumpingEntry) existing;
                updatedModel = repository.updateEntry(
                    new PumpingEntry(p.getId(), p.getChildId(), newStart, newEnd, p.getNotes(), p.getAmount(), null, p.getTags())
                );
            }
            return new TimeEntry(entry.type, updatedModel.getId(), updatedModel.getStart(), updatedModel.getEnd(), updatedModel.getNotes());
        }, callback);
    }

    private static Date parseIsoSafe(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_STRING, Locale.ENGLISH);
            String cleaned = raw.replaceAll("\\.[0-9]+([+-Z])", "$1").replaceAll("Z$", "+00:00");
            return sdf.parse(cleaned);
        } catch (ParseException e) {
            return null;
        }
    }

    public void cleanup() {
        ioExecutor.shutdown();
    }
}
