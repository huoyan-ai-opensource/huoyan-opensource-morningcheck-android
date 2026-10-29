package cc.huoyan.opensource.morningcheck.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import cc.huoyan.opensource.morningcheck.domain.RecordDateRange;

/**
 * 所有磁盘操作由业务的单个 IO 执行器串行调用，列表按筛选条件分页读取。
 */
public final class AppStore extends SQLiteOpenHelper {
    public static final int DEFAULT_PAGE_SIZE = 20;
    private static final int DATABASE_VERSION = 6;
    private final File photos;

    public AppStore(Context context) {
        this(context, "morningcheck.db");
    }

    // 隔离的数据库用例使用独立文件名，不接触设备业务库。
    AppStore(Context context, String databaseName) {
        super(context, databaseName, null, DATABASE_VERSION);
        photos = new File(context.getFilesDir(), "check-photos");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE staff (id TEXT PRIMARY KEY, data TEXT NOT NULL, time INTEGER NOT NULL, name TEXT NOT NULL DEFAULT '', department TEXT NOT NULL DEFAULT '', staff_no TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1, department_id INTEGER NOT NULL DEFAULT 0, person_id INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, data TEXT NOT NULL, time INTEGER NOT NULL, status INTEGER NOT NULL DEFAULT -1, upload TEXT NOT NULL DEFAULT '', staff_id TEXT NOT NULL DEFAULT '', staff_name TEXT NOT NULL DEFAULT '', staff_no TEXT NOT NULL DEFAULT '', department_id INTEGER NOT NULL DEFAULT 0)");
        createDepartments(db);
        createPageIndexes(db);
        ensureStaffNumberStorage(db);
        ensureStaffIdentityStorage(db);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!db.isReadOnly()) {
            ensureStaffNumberStorage(db);
            ensureStaffIdentityStorage(db);
            // 旧库同样补员工复检查询索引，无需迁移或重写历史记录。
            db.execSQL("CREATE INDEX IF NOT EXISTS records_staff_latest ON records(staff_id,time DESC,id DESC,status)");
        }
    }

    private static void ensureStaffNumberStorage(SQLiteDatabase db) {
        // 保留旧员工 ID 与 SDK 人脸关联，只补查询列与不回退的编号计数器。
        boolean hasNumber = false;
        try (Cursor columns = db.rawQuery("PRAGMA table_info(staff)", null)) {
            while (columns.moveToNext())
                if ("staff_no".equals(columns.getString(1))) hasNumber = true;
        }
        db.beginTransaction();
        try {
            if (!hasNumber) {
                db.execSQL("ALTER TABLE staff ADD COLUMN staff_no TEXT NOT NULL DEFAULT ''");
                try (Cursor rows = db.query("staff", new String[]{"id", "data"}, null, null, null, null, null)) {
                    while (rows.moveToNext()) {
                        try {
                            ContentValues number = new ContentValues();
                            number.put("staff_no", new JSONObject(rows.getString(1)).optString("staff_no").trim());
                            db.update("staff", number, "id=?", new String[]{rows.getString(0)});
                        } catch (JSONException error) {
                            throw new IllegalStateException("本地员工数据损坏，请保留数据联系管理员", error);
                        }
                    }
                }
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS staff_number_search ON staff(staff_no)");
            db.execSQL("CREATE TABLE IF NOT EXISTS staff_id_sequence (singleton INTEGER PRIMARY KEY CHECK(singleton=1), value INTEGER NOT NULL)");
            db.execSQL("INSERT OR IGNORE INTO staff_id_sequence(singleton,value) VALUES(1,0)");
            db.execSQL("UPDATE staff_id_sequence SET value=MAX(value,COALESCE((SELECT MAX(CAST(id AS INTEGER)) FROM staff),0)) WHERE singleton=1");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void ensureStaffIdentityStorage(SQLiteDatabase db) {
        boolean legacyIdentity = !hasColumn(db, "staff", "person_id");
        db.beginTransaction();
        try {
            addColumn(db, "staff", "person_id", "INTEGER NOT NULL DEFAULT 0");
            addColumn(db, "staff", "deleted", "INTEGER NOT NULL DEFAULT 0");
            if (legacyIdentity) {
                // 旧 SDK 底库使用本地主键；只在首次升级回填，不能在重开或资料更新时重算。
                try (Cursor rows = db.query("staff", new String[]{"id"}, null, null, null, null, null)) {
                    while (rows.moveToNext()) {
                        long personId;
                        try {
                            personId = Long.parseLong(rows.getString(0));
                        } catch (NumberFormatException invalid) {
                            throw new IllegalStateException("本地员工编号无效，无法保留人脸关联", invalid);
                        }
                        if (personId <= 0)
                            throw new IllegalStateException("本地员工编号无效，无法保留人脸关联");
                        ContentValues fields = new ContentValues();
                        fields.put("person_id", personId);
                        db.update("staff", fields, "id=?", new String[]{rows.getString(0)});
                    }
                }
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS staff_person_lookup ON staff(person_id,deleted)");
            db.execSQL("CREATE INDEX IF NOT EXISTS staff_live_page ON staff(deleted,time DESC,id DESC)");
            db.execSQL("CREATE TABLE IF NOT EXISTS staff_person_sequence (singleton INTEGER PRIMARY KEY CHECK(singleton=1), value INTEGER NOT NULL)");
            db.execSQL("INSERT OR IGNORE INTO staff_person_sequence(singleton,value) VALUES(1,0)");
            db.execSQL("UPDATE staff_person_sequence SET value=MAX(value,COALESCE((SELECT MAX(person_id) FROM staff),0)) WHERE singleton=1");
            if (legacyIdentity) {
                // 旧版曾硬删除的人员也可能仍在 SDK 中；旧本地主键的已分配区间一并保留。
                db.execSQL("UPDATE staff_person_sequence SET value=MAX(value,(SELECT value FROM staff_id_sequence WHERE singleton=1)) WHERE singleton=1");
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion >= 5) {
            ensureStaffNumberStorage(db);
            ensureStaffIdentityStorage(db);
            return;
        }
        // 只补查询列，原始 JSON、员工主键、人脸关联和记录快照全部保留。
        addColumn(db, "staff", "name", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "staff", "department", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "staff", "staff_no", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "staff", "enabled", "INTEGER NOT NULL DEFAULT 1");
        addColumn(db, "staff", "department_id", "INTEGER NOT NULL DEFAULT 0");
        addColumn(db, "records", "status", "INTEGER NOT NULL DEFAULT -1");
        addColumn(db, "records", "upload", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "records", "staff_id", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "records", "staff_name", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "records", "staff_no", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "records", "department_id", "INTEGER NOT NULL DEFAULT 0");
        if (!tableExists(db, "departments")) {
            createDepartments(db);
        } else if (!hasColumn(db, "departments", "parent_id")) {
            // SQLite 无法原地去掉旧 name 全局唯一约束，按原 ID 复制到新的同级唯一表。
            db.execSQL("ALTER TABLE departments RENAME TO departments_legacy");
            createDepartments(db);
            db.execSQL("INSERT INTO departments(id,name,time,parent_id,path) SELECT id,name,time,0,name FROM departments_legacy");
            try (Cursor counter = db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='departments_legacy'", null)) {
                if (counter.moveToFirst()) {
                    long previous = counter.getLong(0);
                    if (count(db, "sqlite_sequence", "name=?", new String[]{"departments"}) == 0) {
                        ContentValues sequence = new ContentValues();
                        sequence.put("name", "departments");
                        sequence.put("seq", previous);
                        db.insertOrThrow("sqlite_sequence", null, sequence);
                    } else
                        db.execSQL("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name='departments'", new Object[]{previous});
                }
            }
            db.execSQL("DROP TABLE departments_legacy");
        }
        ensureStaffNumberStorage(db);
        ensureStaffIdentityStorage(db);
        backfillQueryFields(db);
        refreshDepartmentPaths(db);
        createPageIndexes(db);
    }

    private static void createDepartments(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE departments (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, time INTEGER NOT NULL, parent_id INTEGER NOT NULL DEFAULT 0, path TEXT NOT NULL DEFAULT '', UNIQUE(parent_id,name))");
    }

    private static void createPageIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS staff_page_order ON staff(time DESC, id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS staff_department_members ON staff(department)");
        db.execSQL("CREATE INDEX IF NOT EXISTS staff_enabled_page ON staff(enabled,time DESC,id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS staff_department_page ON staff(department_id,enabled,time DESC,id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS records_page_order ON records(time DESC, id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS records_status_page ON records(status, time DESC, id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS records_upload_page ON records(upload, time DESC, id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS records_today_staff ON records(time, staff_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS records_staff_latest ON records(staff_id,time DESC,id DESC,status)");
        db.execSQL("CREATE INDEX IF NOT EXISTS records_department_page ON records(department_id,status,time DESC,id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS departments_parent_order ON departments(parent_id,name,id)");
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return cursor.moveToFirst();
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) if (column.equals(cursor.getString(1))) return true;
            return false;
        }
    }

    private static void addColumn(SQLiteDatabase db, String table, String column, String type) {
        if (!hasColumn(db, table, column))
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    private static JSONObject decodeForMigration(String raw) {
        try {
            return new JSONObject(raw);
        }
        // 损坏行也保留原文与查询列，读详情时继续报告数据损坏，不因一行错误清空整库。
        catch (JSONException ignored) {
            return null;
        }
    }

    private static void backfillQueryFields(SQLiteDatabase db) {
        DepartmentTree tree = departmentTree(db);
        try (Cursor rows = db.query("staff", new String[]{"id", "data", "name", "department", "staff_no", "enabled", "department_id"}, null, null, null, null, null)) {
            while (rows.moveToNext()) {
                JSONObject raw = decodeForMigration(rows.getString(1));
                String department = raw == null ? rows.getString(3) : raw.optString("department", rows.getString(3)).trim();
                if (!department.isEmpty() && !tree.hasMatch(department)) {
                    ensureDepartment(db, department);
                    tree = departmentTree(db);
                }
                ContentValues fields = new ContentValues();
                fields.put("name", raw == null ? rows.getString(2) : raw.optString("name", rows.getString(2)));
                fields.put("staff_no", raw == null ? rows.getString(4) : raw.optString("staff_no").trim());
                fields.put("enabled", raw == null ? rows.getInt(5) : raw.optBoolean("enabled", true) ? 1 : 0);
                long departmentId = raw == null && rows.getLong(6) > 0 ? rows.getLong(6)
                        : raw != null && raw.has("department_id") ? raw.optLong("department_id") : tree.resolve(department);
                if (departmentId < 0 || !tree.contains(departmentId)) departmentId = 0;
                fields.put("department_id", departmentId);
                fields.put("department", departmentId > 0 ? tree.path(departmentId) : department);
                db.update("staff", fields, "id=?", new String[]{rows.getString(0)});
            }
        }
        try (Cursor rows = db.query("records", new String[]{"id", "data", "staff_id", "status", "upload"}, null, null, null, null, null)) {
            while (rows.moveToNext()) {
                JSONObject raw = decodeForMigration(rows.getString(1));
                if (raw == null) continue;
                JSONObject queryData = Jsons.copy(raw);
                if (!queryData.has("staff_id")) Jsons.put(queryData, "staff_id", rows.getString(2));
                if (!queryData.has("status")) Jsons.put(queryData, "status", rows.getInt(3));
                if (!queryData.has("upload")) Jsons.put(queryData, "upload", rows.getString(4));
                ContentValues fields = recordQueryFields(db, queryData, tree, false);
                db.update("records", fields, "id=?", new String[]{rows.getString(0)});
            }
        }
    }

    private static DepartmentTree departmentTree(SQLiteDatabase db) {
        List<JSONObject> values = new ArrayList<>();
        try (Cursor cursor = db.query("departments", new String[]{"id", "name", "parent_id"}, null, null, null, null, null)) {
            while (cursor.moveToNext())
                values.add(Jsons.obj("id", cursor.getLong(0), "name", cursor.getString(1), "parent_id", cursor.getLong(2)));
        }
        return new DepartmentTree(values);
    }

    private static void refreshDepartmentPaths(SQLiteDatabase db) {
        for (JSONObject department : departmentTree(db).options(0)) {
            ContentValues fields = new ContentValues();
            fields.put("path", department.optString("path"));
            db.update("departments", fields, "id=?", new String[]{department.optString("id")});
        }
    }

    public static final class DataPage {
        public final List<JSONObject> rows;
        public final int page, pageSize, totalPages;
        public final long total, totalRows;

        private DataPage(List<JSONObject> rows, int page, int pageSize, long total, long totalRows) {
            this.rows = rows;
            this.page = page;
            this.pageSize = pageSize;
            this.total = total;
            this.totalRows = totalRows;
            this.totalPages = (int) Math.max(1L, (total + pageSize - 1L) / pageSize);
        }
    }

    public DataPage staffPage(String query, int page, int size) {
        return staffSlice(query, page, size, false);
    }

    public DataPage staffWindow(String query, int loadedPages, int size) {
        return staffSlice(query, loadedPages, size, true);
    }

    private DataPage staffSlice(String query, int page, int size, boolean window) {
        String keyword = query == null ? "" : query.trim();
        // instr 使用字面值匹配，员工姓名中的 % 和 _ 不能被当作 SQL 通配符。
        String selection = keyword.isEmpty() ? null : "instr(name, ?) > 0 OR instr(department, ?) > 0 OR instr(staff_no, ?) > 0";
        String[] args = keyword.isEmpty() ? null : new String[]{keyword, keyword, keyword};
        return readPage("staff", selection, args, page, size, window);
    }

    public DataPage staffPage(String query, int enabledFilter, long departmentId, int page, int size) {
        return staffSlice(query, enabledFilter, departmentId, page, size, false);
    }

    public DataPage staffWindow(String query, int enabledFilter, long departmentId, int loadedPages, int size) {
        return staffSlice(query, enabledFilter, departmentId, loadedPages, size, true);
    }

    private DataPage staffSlice(String query, int enabledFilter, long departmentId, int page, int size, boolean window) {
        if (enabledFilter < 0 || enabledFilter > 2)
            throw new IllegalArgumentException("员工启用状态筛选条件无效");
        List<String> clauses = new ArrayList<>(), args = new ArrayList<>();
        String keyword = query == null ? "" : query.trim();
        if (!keyword.isEmpty()) {
            clauses.add("(instr(name, ?) > 0 OR instr(staff_no, ?) > 0)");
            args.add(keyword);
            args.add(keyword);
        }
        if (enabledFilter > 0) clauses.add("enabled=" + (enabledFilter == 1 ? 1 : 0));
        addDepartmentSelection(getReadableDatabase(), clauses, departmentId);
        return readPage("staff", selection(clauses), args.toArray(new String[0]), page, size, window);
    }

    public DataPage departmentsPage(String query, int page, int size) {
        return departmentsPageInternal(query, -1, page, size);
    }

    public DataPage departmentsPage(String query, long parentId, int page, int size) {
        if (parentId < 0) throw new IllegalArgumentException("上级部门编号无效");
        return departmentsPageInternal(query, parentId, page, size);
    }

    /** 完整目录只读取一次，树排序与深度计算由调用方在 IO 队列中完成。 */
    public List<JSONObject> departmentTreeRows(String query) {
        String keyword = query == null ? "" : query.trim();
        List<JSONObject> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("departments", departmentColumns(),
                keyword.isEmpty() ? null : "instr(path, ?) > 0",
                keyword.isEmpty() ? null : new String[]{keyword}, null, null,
                "name COLLATE NOCASE ASC, id ASC")) {
            while (cursor.moveToNext()) rows.add(departmentRow(cursor));
        }
        return rows;
    }

    private DataPage departmentsPageInternal(String query, long parentId, int page, int size) {
        if (page < 1 || size < 1 || size > 100)
            throw new IllegalArgumentException("本地分页参数无效");
        String keyword = query == null ? "" : query.trim();
        String selection = keyword.isEmpty() ? (parentId < 0 ? null : "parent_id=" + parentId) : "instr(path, ?) > 0";
        String[] args = keyword.isEmpty() ? null : new String[]{keyword};
        SQLiteDatabase db = getReadableDatabase();
        long totalRows = count(db, "departments", null, null);
        long total = selection == null ? totalRows : count(db, "departments", selection, args);
        int actualPage = (int) Math.min(page, Math.max(1L, (total + size - 1L) / size));
        List<JSONObject> rows = new ArrayList<>();
        try (Cursor cursor = db.query("departments", departmentColumns(), selection, args, null, null,
                "name COLLATE NOCASE ASC, id ASC", ((long) actualPage - 1L) * size + "," + size)) {
            while (cursor.moveToNext()) rows.add(departmentRow(cursor));
        }
        return new DataPage(rows, actualPage, size, total, totalRows);
    }

    public JSONObject department(long id) {
        try (Cursor cursor = getReadableDatabase().query("departments", departmentColumns(), "id=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return cursor.moveToFirst() ? departmentRow(cursor) : null;
        }
    }

    private static String[] departmentColumns() {
        return new String[]{"id", "name", "parent_id", "path",
                "(SELECT COUNT(*) FROM staff WHERE staff.department_id=departments.id AND staff.deleted=0) AS member_count",
                "(SELECT COUNT(*) FROM departments AS child WHERE child.parent_id=departments.id) AS child_count"};
    }

    private static JSONObject departmentRow(Cursor cursor) {
        return Jsons.obj("id", cursor.getLong(0), "name", cursor.getString(1), "parent_id", cursor.getLong(2),
                "path", cursor.getString(3), "member_count", cursor.getLong(4), "child_count", cursor.getLong(5));
    }

    public List<JSONObject> departmentOptions(long excludedSubtreeId) {
        return departmentTree(getReadableDatabase()).options(excludedSubtreeId);
    }

    public long saveDepartment(long id, String name) {
        return saveDepartment(id, name, null);
    }

    public long saveDepartment(long id, String name, String expectedName) {
        JSONObject original = id == 0 ? null : department(id);
        long parentId = original == null ? 0 : original.optLong("parent_id");
        return saveDepartment(id, name, parentId, expectedName, parentId);
    }

    public long saveDepartment(long id, String name, long parentId, String expectedName, long expectedParentId) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) throw new IllegalArgumentException("请输入部门名称");
        if (id < 0 || parentId < 0) throw new IllegalArgumentException("部门编号无效");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject original = id == 0 ? null : department(id);
            if (id > 0 && original == null)
                throw new IllegalStateException("部门已删除，请返回列表刷新");
            if (original != null && expectedName != null && !original.optString("name").equals(expectedName))
                throw new IllegalStateException("部门名称已变更，请重新打开后修改");
            if (original != null && original.optLong("parent_id") != expectedParentId)
                throw new IllegalStateException("上级部门已变更，请重新打开后修改");
            DepartmentTree tree = departmentTree(db);
            if (parentId > 0 && !tree.contains(parentId))
                throw new IllegalStateException("上级部门不存在，请重新选择部门");
            if (id > 0 && tree.subtree(id).contains(parentId))
                throw new IllegalArgumentException("上级部门不能选择自身或下级部门");
            if (count(db, "departments", "parent_id=? AND name=? AND id<>?", new String[]{String.valueOf(parentId), cleanName, String.valueOf(id)}) > 0)
                throw new IllegalArgumentException("该上级部门下的部门名称已存在");
            ContentValues fields = new ContentValues();
            fields.put("name", cleanName);
            fields.put("parent_id", parentId);
            if (original == null) {
                fields.put("time", System.currentTimeMillis());
                id = db.insertOrThrow("departments", null, fields);
            } else {
                db.update("departments", fields, "id=?", new String[]{String.valueOf(id)});
            }
            refreshDepartmentPaths(db);
            if (original != null && (!original.optString("name").equals(cleanName) || original.optLong("parent_id") != parentId))
                refreshStaffDepartmentPaths(db, departmentTree(db).subtree(id));
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    private static void refreshStaffDepartmentPaths(SQLiteDatabase db, Set<Long> departmentIds) {
        DepartmentTree tree = departmentTree(db);
        String lastId = null;
        while (true) {
            List<JSONObject> batch = new ArrayList<>();
            String selection = "deleted=0 AND department_id IN (" + departmentIdsSql(departmentIds) + ")" + (lastId == null ? "" : " AND id>?");
            String[] args = lastId == null ? null : new String[]{lastId};
            try (Cursor cursor = db.query("staff", new String[]{"id", "data", "department_id"}, selection, args,
                    null, null, "id ASC", String.valueOf(DEFAULT_PAGE_SIZE))) {
                while (cursor.moveToNext()) {
                    JSONObject person = decode(cursor.getString(1));
                    Jsons.put(person, "department_id", cursor.getLong(2));
                    batch.add(person);
                    lastId = cursor.getString(0);
                }
            }
            if (batch.isEmpty()) return;
            for (JSONObject person : batch) {
                String path = tree.path(person.optLong("department_id"));
                Jsons.put(person, "department", path);
                ContentValues fields = new ContentValues();
                fields.put("department", path);
                fields.put("data", person.toString());
                db.update("staff", fields, "id=?", new String[]{person.optString("id")});
            }
        }
    }

    public void removeDepartment(long id) {
        removeDepartment(id, null);
    }

    public void removeDepartment(long id, String expectedName) {
        JSONObject current = department(id);
        removeDepartment(id, expectedName, current == null ? 0 : current.optLong("parent_id"));
    }

    public void removeDepartment(long id, String expectedName, long expectedParentId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject current = department(id);
            if (current == null) throw new IllegalStateException("部门已删除，请返回列表刷新");
            if (expectedName != null && !current.optString("name").equals(expectedName))
                throw new IllegalStateException("部门名称已变更，请重新打开后确认删除");
            if (current.optLong("parent_id") != expectedParentId)
                throw new IllegalStateException("上级部门已变更，请重新打开后确认删除");
            long members = current.optLong("member_count");
            if (current.optLong("child_count") > 0)
                throw new IllegalStateException("该部门还有下级部门，请先调整下级部门后再删除");
            if (members > 0)
                throw new IllegalStateException("该部门还有 " + members + " 名员工，请先在员工编辑弹窗中调整部门后再删除");
            if (count(db, "records", "department_id=?", new String[]{String.valueOf(id)}) > 0)
                throw new IllegalStateException("该部门存在历史晨检记录，不能删除");
            db.delete("departments", "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void ensureDepartment(SQLiteDatabase db, String name) {
        if (name.isEmpty() || departmentTree(db).hasMatch(name)) return;
        ContentValues fields = new ContentValues();
        fields.put("name", name);
        fields.put("time", System.currentTimeMillis());
        fields.put("parent_id", 0);
        fields.put("path", name);
        db.insertOrThrow("departments", null, fields);
    }

    private static void requireExistingDepartment(SQLiteDatabase db, JSONObject person) {
        long id = person.optLong("department_id");
        if (person.has("department_id")) {
            if (id < 0 || (id > 0 && !departmentTree(db).contains(id)))
                throw new IllegalStateException("选择的部门已删除，请重新选择部门");
            return;
        }
        String name = person.optString("department").trim();
        if (!name.isEmpty() && departmentTree(db).resolve(name) == 0)
            throw new IllegalStateException("选择的部门已删除、改名或存在同名部门，请重新选择部门");
    }

    private static String selection(List<String> clauses) {
        return clauses.isEmpty() ? null : android.text.TextUtils.join(" AND ", clauses);
    }

    private static String departmentIdsSql(Set<Long> ids) {
        // ID 来自本地整数主键，直接写数字可避免大部门超过 SQLite 999 个绑定参数上限。
        return ids.isEmpty() ? "0" : android.text.TextUtils.join(",", ids);
    }

    private static void addDepartmentSelection(SQLiteDatabase db, List<String> clauses, long departmentId) {
        if (departmentId < -1) throw new IllegalArgumentException("部门筛选条件无效");
        if (departmentId == -1) clauses.add("department_id=0");
        else if (departmentId > 0) {
            Set<Long> ids = departmentTree(db).subtree(departmentId);
            clauses.add(ids.isEmpty() ? "1=0" : "department_id IN (" + departmentIdsSql(ids) + ")");
        }
    }

    public DataPage recordsPage(int filter, int page, int size) {
        String selection;
        String[] args = null;
        switch (filter) {
            case 0:
                selection = null;
                break;
            case 1:
                selection = "status=0";
                break;
            case 2:
                selection = "upload<>?";
                args = new String[]{"已上传"};
                break;
            case 3:
                selection = "upload=?";
                args = new String[]{"结果待核实"};
                break;
            default:
                throw new IllegalArgumentException("晨检记录筛选条件无效");
        }
        return readPage("records", selection, args, page, size);
    }

    public DataPage recordsPage(String query, int resultFilter, long departmentId, int page, int size) {
        return recordsPage(query, resultFilter, departmentId, RecordDateRange.Bounds.all(), page, size);
    }

    public DataPage recordsPage(String query, int resultFilter, long departmentId,
                                RecordDateRange.Bounds dates, int page, int size) {
        return recordsSlice(query, resultFilter, departmentId, dates, page, size, false);
    }

    public DataPage recordsWindow(String query, int resultFilter, long departmentId,
                                  RecordDateRange.Bounds dates, int loadedPages, int size) {
        return recordsSlice(query, resultFilter, departmentId, dates, loadedPages, size, true);
    }

    private DataPage recordsSlice(String query, int resultFilter, long departmentId,
                                  RecordDateRange.Bounds dates, int page, int size, boolean window) {
        if (resultFilter < 0 || resultFilter > 2)
            throw new IllegalArgumentException("晨检结果筛选条件无效");
        if (dates == null) throw new IllegalArgumentException("晨检日期筛选条件无效");
        List<String> clauses = new ArrayList<>(), args = new ArrayList<>();
        String keyword = query == null ? "" : query.trim();
        if (!keyword.isEmpty()) {
            clauses.add("(instr(staff_name, ?) > 0 OR instr(staff_no, ?) > 0)");
            args.add(keyword);
            args.add(keyword);
        }
        if (resultFilter > 0) clauses.add("status=" + (resultFilter == 1 ? 1 : 0));
        addDepartmentSelection(getReadableDatabase(), clauses, departmentId);
        if (dates.bounded) {
            // 使用已有 time 索引；计数和分页复用此范围，不读取全库 JSON 做日期筛选。
            clauses.add("time>=? AND time<?");
            args.add(String.valueOf(dates.startInclusive));
            args.add(String.valueOf(dates.endExclusive));
        }
        return readPage("records", selection(clauses), args.toArray(new String[0]), page, size, window);
    }

    private DataPage readPage(String table, String selection, String[] args, int page, int size) {
        return readPage(table, selection, args, page, size, false);
    }

    private DataPage readPage(String table, String selection, String[] args, int page, int size, boolean window) {
        if (page < 1 || size < 1 || size > 100)
            throw new IllegalArgumentException("本地分页参数无效");
        SQLiteDatabase db = getReadableDatabase();
        String allSelection = "staff".equals(table) ? "deleted=0" : null;
        if (allSelection != null)
            selection = selection == null ? allSelection : allSelection + " AND (" + selection + ")";
        long totalRows = count(db, table, allSelection, null);
        long total = selection == null ? totalRows : count(db, table, selection, args);
        int totalPages = (int) Math.max(1L, (total + size - 1L) / size);
        int actualPage = Math.min(page, totalPages);
        List<JSONObject> rows = new ArrayList<>();
        String[] columns = dataColumns(table);
        // 返回已加载范围时使用一条 LIMIT 查询，不能逐页重算总数、重复读取旧数据。
        String limit = window ? "0," + (long) actualPage * size : ((long) actualPage - 1L) * size + "," + size;
        try (Cursor cursor = db.query(table, columns, selection, args, null, null,
                "time DESC, id DESC", limit)) {
            while (cursor.moveToNext()) rows.add(readDataRow(table, cursor));
        }
        return new DataPage(rows, actualPage, size, total, totalRows);
    }

    private static long count(SQLiteDatabase db, String table, String selection, String[] args) {
        try (Cursor cursor = db.query(table, new String[]{"COUNT(*)"}, selection, args, null, null, null)) {
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }

    public static final class HomeSummary {
        public final long staffCount, checkedTodayCount, normalTodayCount, abnormalTodayCount;

        private HomeSummary(long staffCount, long normalTodayCount, long abnormalTodayCount) {
            this.staffCount = staffCount;
            this.normalTodayCount = normalTodayCount;
            this.abnormalTodayCount = abnormalTodayCount;
            this.checkedTodayCount = normalTodayCount + abnormalTodayCount;
        }
    }

    public long staffCount() {
        return count(getReadableDatabase(), "staff", "deleted=0", null);
    }

    public HomeSummary homeSummary(long dayStart, long nextDayStart) {
        if (nextDayStart <= dayStart) throw new IllegalArgumentException("晨检统计时间范围无效");
        SQLiteDatabase db = getReadableDatabase();
        // 名册总数与结果聚合放在同一读快照；并发员工增删不能造成“已检 > 员工”。
        // 复检只更新该员工今日结果，计数保持“正常 + 异常 = 已检”；同毫秒按记录 ID 排序。
        String latest = "SELECT (SELECT COUNT(*) FROM staff WHERE deleted=0),COALESCE(SUM(CASE WHEN r.status=1 THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN r.status=0 THEN 1 ELSE 0 END),0) FROM records r"
                + " INNER JOIN staff s ON r.staff_id=s.id WHERE s.deleted=0 AND r.time>=? AND r.time<? AND r.status IN (0,1)"
                + " AND NOT EXISTS (SELECT 1 FROM records newer WHERE newer.staff_id=r.staff_id"
                + " AND newer.time>=? AND newer.time<? AND newer.status IN (0,1)"
                + " AND (newer.time>r.time OR (newer.time=r.time AND newer.id>r.id)))";
        try (Cursor cursor = db.rawQuery(latest, new String[]{String.valueOf(dayStart), String.valueOf(nextDayStart),
                String.valueOf(dayStart), String.valueOf(nextDayStart)})) {
            cursor.moveToFirst();
            return new HomeSummary(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2));
        }
    }

    private static JSONObject decode(String data) {
        try {
            return new JSONObject(data);
        } catch (JSONException error) {
            throw new IllegalStateException("本地数据损坏，请保留数据联系管理员", error);
        }
    }

    public List<JSONObject> staff() {
        return read("staff");
    }

    public List<JSONObject> records() {
        return read("records");
    }

    public JSONObject staff(long id) {
        return readOne("staff", String.valueOf(id));
    }

    /**
     * SDK 标识与本地主键分开查询；损坏数据出现重号时拒绝识别，不选取任意员工。
     */
    public JSONObject staffByPersonId(long personId) {
        if (personId <= 0) return null;
        try (Cursor cursor = getReadableDatabase().query("staff", dataColumns("staff"), "person_id=?",
                new String[]{String.valueOf(personId)}, null, null, null, "2")) {
            if (!cursor.moveToFirst()) return null;
            if (cursor.getCount() > 1)
                throw new IllegalStateException("SDK 人员标识对应多个员工，请联系管理员核对");
            return cursor.getInt(4) == 0 ? readDataRow("staff", cursor) : null;
        }
    }

    public JSONObject record(String id) {
        return readOne("records", id);
    }

    private JSONObject readOne(String table, String id) {
        String[] columns = dataColumns(table);
        String selection = "staff".equals(table) ? "id=? AND deleted=0" : "id=?";
        try (Cursor cursor = getReadableDatabase().query(table, columns, selection,
                new String[]{id}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            return readDataRow(table, cursor);
        }
    }

    private static String[] dataColumns(String table) {
        return "staff".equals(table)
                ? new String[]{"data", "department_id", "department", "person_id", "deleted"} : new String[]{"data"};
    }

    private static JSONObject readDataRow(String table, Cursor cursor) {
        JSONObject value = decode(cursor.getString(0));
        if ("staff".equals(table)) {
            // 迁移不重写原始 JSON；人员身份、软删除和部门归属以专用列为准。
            Jsons.put(value, "department_id", cursor.getLong(1));
            Jsons.put(value, "department", cursor.getString(2));
            Jsons.put(value, "person_id", cursor.getLong(3));
            Jsons.put(value, "deleted", cursor.getInt(4) != 0);
        }
        return value;
    }

    private List<JSONObject> read(String table) {
        List<JSONObject> out = new ArrayList<>();
        String[] columns = dataColumns(table);
        String selection = "staff".equals(table) ? "deleted=0" : null;
        try (Cursor cursor = getReadableDatabase().query(table, columns, selection, null, null, null, "time DESC")) {
            while (cursor.moveToNext()) {
                out.add(readDataRow(table, cursor));
            }
        }
        return out;
    }

    public void saveStaff(JSONObject data) {
        if (data == null) throw new IllegalArgumentException("本地员工数据无效");
        if (data.optLong("id") <= 0)
            throw new IllegalArgumentException("本地员工编号无效，不能保存");
        JSONObject normalized = Jsons.copy(data);
        String staffNumber = data.optString("staff_no").trim();
        Jsons.put(normalized, "staff_no", staffNumber);
        String department = data.optString("department").trim();
        if (!department.equals(data.optString("department"))) {
            Jsons.put(normalized, "department", department);
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long personId = 0;
            try (Cursor row = db.query("staff", new String[]{"person_id", "deleted"}, "id=?",
                    new String[]{normalized.optString("id")}, null, null, null)) {
                if (row.moveToFirst()) {
                    if (row.getInt(1) != 0)
                        throw new IllegalStateException("员工档案已删除，不能恢复或覆盖");
                    personId = row.getLong(0);
                    if (personId <= 0)
                        throw new IllegalStateException("员工人脸关联无效，请联系管理员核对");
                    if (data.has("person_id") && !samePersonId(data.opt("person_id"), personId))
                        throw new IllegalArgumentException("员工的 SDK 人员标识不可修改");
                }
            }
            if (data.optBoolean("deleted"))
                throw new IllegalArgumentException("请通过删除员工操作停用档案");
            if (personId == 0) personId = nextPersonId(db);
            Jsons.put(normalized, "person_id", personId);
            Jsons.put(normalized, "deleted", false);
            JSONObject current = staff(normalized.optLong("id"));
            if (current == null || !current.optString("staff_no").trim().equals(staffNumber)) {
                StaffPolicy.requireStaffNumber(staffNumber);
                requireUniqueStaffNumber(db, staffNumber, normalized.optLong("id"));
            }
            normalizeStaffDepartment(db, normalized);
            save("staff", normalized);
            db.execSQL("UPDATE staff_id_sequence SET value=MAX(value,?) WHERE singleton=1", new Object[]{normalized.optLong("id")});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static boolean samePersonId(Object value, long expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    private static long nextPersonId(SQLiteDatabase db) {
        // 仅由保存事务调用；失败整体回滚，成功后的高水位不随删除或工号复用回退。
        long previous;
        try (Cursor counter = db.rawQuery("SELECT value FROM staff_person_sequence WHERE singleton=1", null)) {
            if (!counter.moveToFirst()) throw new IllegalStateException("SDK 人员标识生成失败");
            previous = counter.getLong(0);
        }
        if (previous == Long.MAX_VALUE) throw new IllegalStateException("SDK 人员标识已达到上限");
        long next = previous + 1L;
        db.execSQL("UPDATE staff_person_sequence SET value=? WHERE singleton=1", new Object[]{next});
        return next;
    }

    public void saveRecord(JSONObject data) {
        save("records", data);
    }

    private static void normalizeStaffDepartment(SQLiteDatabase db, JSONObject person) {
        DepartmentTree tree = departmentTree(db);
        String text = person.optString("department").trim();
        long id;
        if (person.has("department_id")) {
            id = person.optLong("department_id");
            if (id < 0 || (id > 0 && !tree.contains(id)))
                throw new IllegalStateException("选择的部门已删除，请重新选择部门");
        } else {
            ensureDepartment(db, text);
            tree = departmentTree(db);
            id = tree.resolve(text);
        }
        Jsons.put(person, "department_id", id);
        Jsons.put(person, "department", id > 0 ? tree.path(id) : text);
    }

    public void createStaff(JSONObject data) {
        createStaffChecked(data, false);
    }

    /**
     * 页面只能选择已有部门；云名册导入仍由 saveStaff 收录自由文本部门。
     */
    public long createStaffProfile(JSONObject data) {
        if (data == null) throw new IllegalArgumentException("本地员工数据无效");
        JSONObject value = Jsons.copy(data);
        Jsons.put(value, "staff_no", StaffPolicy.requireStaffNumber(value.optString("staff_no")));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireExistingDepartment(db, value);
            long previous;
            try (Cursor counter = db.rawQuery("SELECT value FROM staff_id_sequence WHERE singleton=1", null)) {
                if (!counter.moveToFirst()) throw new IllegalStateException("员工编号生成失败");
                previous = counter.getLong(0);
            }
            if (previous == Long.MAX_VALUE) throw new IllegalStateException("员工编号已达到上限");
            long id = previous + 1L;
            Jsons.put(value, "id", id);
            saveStaff(value);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    private static void requireUniqueStaffNumber(SQLiteDatabase db, String number, long ownerId) {
        // 历史空工号仍可接受人脸回执；资料新增/编辑必须先走必填校验。
        if (number.isEmpty()) return;
        try (Cursor matches = db.query("staff", new String[]{"id"}, "deleted=0 AND staff_no=? AND id<>?",
                new String[]{number, String.valueOf(ownerId)}, null, null, null, "1")) {
            if (matches.moveToFirst())
                throw new IllegalArgumentException("该工号已被其他员工使用，请更换工号");
        }
    }

    private void createStaffChecked(JSONObject data, boolean requireDepartment) {
        if (data == null) throw new IllegalArgumentException("本地员工数据无效");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (staff(data.optLong("id")) != null)
                throw new IllegalArgumentException("该员工编号已存在");
            StaffPolicy.requireStaffNumber(data.optString("staff_no"));
            if (requireDepartment) requireExistingDepartment(db, data);
            saveStaff(data);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public JSONObject patchStaff(long id, JSONObject changes) {
        JSONObject current = staff(id);
        if (current == null) throw new IllegalStateException("员工档案已删除，请刷新后操作");
        JSONObject updated = Jsons.patch(current, changes);
        if (changes.has("department") && !changes.has("department_id"))
            updated.remove("department_id");
        saveStaff(updated);
        return staff(id);
    }

    /**
     * 表单只合并本次修改字段；后台人脸回执可继续更新，档案冲突则交给操作员重新核对。
     */
    public JSONObject patchStaffProfile(long id, JSONObject expectedProfile, JSONObject changes) {
        if (expectedProfile == null || changes == null)
            throw new IllegalArgumentException("员工编辑快照无效");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject current = staff(id);
            if (current == null) throw new IllegalStateException("员工档案已删除，请返回列表刷新");
            Iterator<String> keys = changes.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"name".equals(key) && !"department".equals(key) && !"department_id".equals(key) && !"staff_no".equals(key)
                        && !"health_expiry".equals(key) && !"enabled".equals(key))
                    throw new IllegalArgumentException("员工编辑字段无效：" + key);
                boolean same = "department_id".equals(key)
                        ? current.optLong(key) == expectedProfile.optLong(key)
                        : "enabled".equals(key)
                          ? current.optBoolean(key, true) == expectedProfile.optBoolean(key, true)
                          : current.optString(key).equals(expectedProfile.optString(key));
                if (!same) throw new IllegalStateException("员工资料已被更新，请重新打开详情后修改");
            }
            JSONObject updated = Jsons.patch(current, changes);
            if (changes.has("department") && !changes.has("department_id"))
                updated.remove("department_id");
            if (changes.has("department") || changes.has("department_id"))
                requireExistingDepartment(db, updated);
            Jsons.put(updated, "staff_no", StaffPolicy.requireStaffNumber(updated.optString("staff_no")));
            requireUniqueStaffNumber(db, updated.optString("staff_no"), id);
            saveStaff(updated);
            db.setTransactionSuccessful();
            return staff(id);
        } finally {
            db.endTransaction();
        }
    }

    public JSONObject patchRecord(String id, JSONObject changes) {
        JSONObject current = record(id);
        if (current == null) throw new IllegalStateException("晨检记录不存在");
        JSONObject updated = Jsons.patch(current, changes);
        saveRecord(updated);
        return updated;
    }

    public void saveStaffBatch(List<JSONObject> values) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (JSONObject value : values) saveStaff(value);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void save(String table, JSONObject data) {
        if (data == null || data.optString("id").trim().isEmpty()) {
            throw new IllegalArgumentException("本地数据缺少 ID，不能保存");
        }
        ContentValues values = queryFields(table, data);
        values.put("id", data.optString("id"));
        values.put("data", data.toString());
        values.put("time", data.optLong("created_at", System.currentTimeMillis()));
        long row = getWritableDatabase().insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (row == -1) throw new SQLiteException("保存本地数据失败：" + table);
    }

    private ContentValues queryFields(String table, JSONObject data) {
        ContentValues fields = new ContentValues();
        if ("staff".equals(table)) {
            fields.put("name", data.optString("name"));
            fields.put("department", data.optString("department"));
            fields.put("staff_no", data.optString("staff_no").trim());
            fields.put("enabled", data.optBoolean("enabled", true) ? 1 : 0);
            fields.put("department_id", data.optLong("department_id"));
            fields.put("person_id", data.optLong("person_id"));
            fields.put("deleted", data.optBoolean("deleted") ? 1 : 0);
        } else {
            return recordQueryFields(getWritableDatabase(), data, departmentTree(getWritableDatabase()), true);
        }
        return fields;
    }

    private static ContentValues recordQueryFields(SQLiteDatabase db, JSONObject data, DepartmentTree tree, boolean keepStoredDepartment) {
        ContentValues fields = new ContentValues();
        fields.put("status", data.optInt("status", -1));
        fields.put("upload", data.optString("upload"));
        fields.put("staff_id", data.optString("staff_id"));
        String name = "", number = "";
        long currentStaffDepartment = 0;
        try (Cursor staff = db.query("staff", new String[]{"name", "staff_no", "department_id"}, "id=? AND deleted=0",
                new String[]{data.optString("staff_id")}, null, null, null)) {
            if (staff.moveToFirst()) {
                name = staff.getString(0);
                number = staff.getString(1);
                currentStaffDepartment = staff.getLong(2);
            }
        }
        fields.put("staff_name", data.has("staff_name") ? data.optString("staff_name") : name);
        fields.put("staff_no", data.has("staff_no") ? data.optString("staff_no").trim() : number);
        long departmentId = 0;
        boolean explicitDepartment = data.has("department_id");
        if (explicitDepartment) {
            // 新记录的 0 是明确未分配，不能被员工后来变更的部门重新解释。
            departmentId = data.optLong("department_id");
        } else {
            boolean retained = false;
            if (keepStoredDepartment) {
                try (Cursor old = db.query("records", new String[]{"data", "department_id"}, "id=?",
                        new String[]{data.optString("id")}, null, null, null)) {
                    if (old.moveToFirst()) {
                        JSONObject snapshot = decodeForMigration(old.getString(0));
                        retained = snapshot != null && snapshot.optString("department").equals(data.optString("department"))
                                && snapshot.optString("staff_id").equals(data.optString("staff_id"));
                        if (retained) departmentId = old.getLong(1);
                    }
                }
            }
            if (!retained) {
                String department = data.optString("department").trim();
                departmentId = tree.resolve(department);
                // 非空旧快照可能属于员工已经离开的部门，名称不再存在也不能改归当前部门。
                // 明确空快照保留未分配，只有完全缺失或 null 才使用员工映射兼容早期数据。
                boolean missingSnapshot = !data.has("department") || data.isNull("department");
                if (departmentId == 0 && missingSnapshot) departmentId = currentStaffDepartment;
            }
        }
        fields.put("department_id", departmentId > 0 && tree.contains(departmentId) ? departmentId : 0);
        return fields;
    }

    public void removeStaff(long id) {
        // 墓碑保留本地主键和 SDK 标识，工号可复用，但原人员身份永不分配给新人。
        ContentValues fields = new ContentValues();
        fields.put("deleted", 1);
        getWritableDatabase().update("staff", fields, "id=? AND deleted=0", new String[]{String.valueOf(id)});
    }

    public void recoverInterruptedUploads() {
        SQLiteDatabase db = getWritableDatabase();
        // 更新后记录立即退出待恢复集合，每批从首条读取，不用 OFFSET，避免跳过记录。
        while (true) {
            List<JSONObject> batch = new ArrayList<>();
            try (Cursor cursor = db.query("records", new String[]{"data"}, "upload=?",
                    new String[]{"上传中"}, null, null, "time DESC, id DESC", String.valueOf(DEFAULT_PAGE_SIZE))) {
                while (cursor.moveToNext()) batch.add(decode(cursor.getString(0)));
            }
            if (batch.isEmpty()) return;
            for (JSONObject item : batch) {
                Jsons.put(item, "upload", "结果待核实");
                Jsons.put(item, "upload_error", "上次上传被中断，请先在云端核对是否已入账");
                saveRecord(item);
            }
        }
    }

    public String savePhoto(String recordId, String stage, Bitmap bitmap) throws IOException {
        if (recordId == null || recordId.trim().isEmpty() || stage == null || stage.trim().isEmpty()) {
            throw new IOException("照片缺少记录或阶段标识");
        }
        if (bitmap == null || bitmap.isRecycled()) throw new IOException("待保存的照片无效");
        if (!photos.isDirectory() && !photos.mkdirs()) throw new IOException("无法创建照片目录");
        File file = photo(recordId + "-" + stage + ".jpg");
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output))
                throw new IOException("照片编码失败");
            output.flush();
            atomic.finishWrite(output);
            return file.getName();
        } catch (IOException | RuntimeException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
    }

    public File photo(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) throw new IOException("无效的照片路径");
        File file = new File(photos, name);
        if (!photos.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())) {
            throw new IOException("无效的照片路径");
        }
        return file;
    }
}
