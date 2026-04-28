package com.rarchives.ripme.ui;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class History {
    private final List<HistoryEntry> list;
    private static final String[] COLUMNS = new String[] {
        "URL",
        "created",
        "modified",
        "latest",
        "#",
        ""
    };

    public History() {
        this.list = new ArrayList<>();
    }

    public void add(HistoryEntry entry) {
        list.add(entry);
    }
    public void remove(HistoryEntry entry) {
        list.remove(entry);
    }
    public void remove(int index) {
        list.remove(index);
    }
    public void clear() {
        list.clear();
    }
    public HistoryEntry get(int index) {
        return list.get(index);
    }
    public String getColumnName(int index) {
        return COLUMNS[index];
    }
    public int getColumnCount() {
        return COLUMNS.length;
    }
    public Object getValueAt(int row, int col) {
        HistoryEntry entry = this.list.get(row);
        switch (col) {
        case 0:
            return entry.url;
        case 1:
            return dateToHumanReadable(entry.startDate);
        case 2:
            return dateToHumanReadable(entry.modifiedDate);
        case 3:
            return entry.latestCount;
        case 4:
            return entry.count;
        case 5:
            return entry.selected;
        default:
            return null;
        }
    }
    private String dateToHumanReadable(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        return sdf.format(date);
    }

    public void moveToBottom(HistoryEntry entry) {
        if (list.remove(entry)) {
            list.add(entry);
        }
    }

    public void sortByModifiedDateAscending() {
        list.sort(Comparator.comparing(entry -> entry.modifiedDate));
    }

    public boolean containsURL(String url) {
        for (HistoryEntry entry : this.list) {
            if (entry.url.equals(url)) {
                return true;
            }
        }
        return false;
    }

    public HistoryEntry getEntryByURL(String url) {
        for (HistoryEntry entry : this.list) {
            if (entry.url.equals(url)) {
                return entry;
            }
        }
        throw new RuntimeException("Could not find URL " + url + " in History");
    }

    private void fromJSON(JSONArray jsonArray) {
        JSONObject json;
        for (int i = 0; i < jsonArray.length(); i++) {
            json = jsonArray.getJSONObject(i);
            list.add(new HistoryEntry().fromJSON(json));
        }
    }

    public void fromFile(String filename) throws IOException {
        try (InputStream is = new FileInputStream(filename)) {
            String jsonString = IOUtils.toString(is, "UTF-8");
            JSONArray jsonArray = new JSONArray(jsonString);
            fromJSON(jsonArray);
        } catch (JSONException e) {
            throw new IOException("Failed to load JSON file " + filename + ": " + e.getMessage(), e);
        }
    }

    public void fromList(List<String> stringList) {
        for (String item : stringList) {
            HistoryEntry entry = new HistoryEntry();
            entry.url = item;
            list.add(entry);
        }
    }

    public void normalizeAndMergeUrls(Function<String, String> normalizer) {
        Map<String, HistoryEntry> merged = new LinkedHashMap<>();
        for (HistoryEntry entry : list) {
            String normalizedUrl = normalizer.apply(entry.url);
            if (normalizedUrl == null || normalizedUrl.isEmpty()) {
                continue;
            }
            entry.url = normalizedUrl;
            HistoryEntry existing = merged.get(normalizedUrl);
            if (existing == null) {
                merged.put(normalizedUrl, entry);
                continue;
            }
            existing.count += entry.count;
            existing.latestCount += entry.latestCount;
            existing.selected = existing.selected || entry.selected;
            if (existing.startDate.after(entry.startDate)) {
                existing.startDate = entry.startDate;
            }
            if (existing.modifiedDate.before(entry.modifiedDate)) {
                existing.modifiedDate = entry.modifiedDate;
            }
            if ((existing.title == null || existing.title.isEmpty()) && entry.title != null && !entry.title.isEmpty()) {
                existing.title = entry.title;
            }
            if ((existing.dir == null || existing.dir.isEmpty()) && entry.dir != null && !entry.dir.isEmpty()) {
                existing.dir = entry.dir;
            }
        }
        list.clear();
        list.addAll(merged.values());
    }

    private JSONArray toJSON() {
        JSONArray jsonArray = new JSONArray();
        for (HistoryEntry entry : list) {
            jsonArray.put(entry.toJSON());
        }
        return jsonArray;
    }

    public List<HistoryEntry> toList() {
        return list;
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public void toFile(String filename) throws IOException {
        try (OutputStream os = new FileOutputStream(filename)) {
            IOUtils.write(toJSON().toString(2), os, "UTF-8");
        }
    }
}
