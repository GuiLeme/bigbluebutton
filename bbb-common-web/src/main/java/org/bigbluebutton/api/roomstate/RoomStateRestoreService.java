package org.bigbluebutton.api.roomstate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.bigbluebutton.api.Util;
import org.bigbluebutton.api2.IBbbWebApiGWApp;
import org.bigbluebutton.presentation.SupportedFileTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads a room state archive (see bbb-export-annotations/workers/state-saver.js
 * for the format) into a running meeting.
 *
 * The archive comes from a moderator and is not trusted: nothing is extracted
 * by the names found in the zip, every entry is read up to a size limit, and
 * the whole archive is validated before the meeting is touched, so a refused
 * archive leaves the meeting as it was.
 *
 * Chat, annotations, notes and layout are sent to akka-apps. The presentation
 * files are stored under new ids and returned to the caller, which hands them
 * to the usual conversion.
 */
public class RoomStateRestoreService {
  private static final Logger log = LoggerFactory.getLogger(RoomStateRestoreService.class);

  public static final String FORMAT = "bbb-room-state";
  public static final int VERSION = 1;

  private static final String MANIFEST_ENTRY = "manifest.json";
  private static final String CHAT_ENTRY = "chat/public.json";
  private static final String NOTES_ENTRY = "notes/notes.json";
  private static final String LAYOUT_ENTRY = "layout.json";
  private static final Pattern PRESENTATION_FILE_PATTERN =
      Pattern.compile("^presentations/(\\d{1,3})/original\\.([a-z0-9]{1,5})$");

  private static final long MAX_MANIFEST_SIZE = 1024 * 1024;
  private static final int MAX_JSON_DEPTH = 64;
  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_ID_LENGTH = 100;
  private static final int MAX_CHAT_MESSAGE_LENGTH = 20000;
  private static final int MAX_PAGES = 5000;
  private static final int ANNOTATIONS_PER_MESSAGE = 200;
  private static final Pattern SIMPLE_VALUE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{0,64}$");
  private static final Set<String> CLIENT_LAYOUT_FLAGS = new HashSet<>(Arrays.asList(
      "sidebarNavigationIsOpen", "sidebarContentIsOpen", "presentationIsOpen", "notesPinned"));
  private static final Set<String> CLIENT_LAYOUT_VALUES = new HashSet<>(Arrays.asList(
      "selectedLayout", "sidebarContentPanel", "cameraDockPosition"));

  private final Gson gson = new Gson();

  private IBbbWebApiGWApp gw;
  private String presentationDir;
  private String toAkkaAppsChannel = "to-akka-apps-redis-channel";
  private long maxZipSize = 200L * 1024 * 1024;
  private long maxUncompressedSize = 500L * 1024 * 1024;
  private int maxEntries = 2000;
  private long maxJsonEntrySize = 20L * 1024 * 1024;
  private long maxPresentationFileSize = 30000000L;
  private int maxPresentations = 20;
  private int maxChatMessages = 5000;
  private int maxAnnotationsPerPresentation = 50000;

  /** A presentation of the archive, stored on disk and waiting for its conversion. */
  public static class RestoredPresentation {
    public final String presentationId;
    public final String name;
    public final File file;
    public final boolean current;
    public final boolean downloadable;
    public final boolean removable;

    RestoredPresentation(String presentationId, String name, File file, boolean current,
                         boolean downloadable, boolean removable) {
      this.presentationId = presentationId;
      this.name = name;
      this.file = file;
      this.current = current;
      this.downloadable = downloadable;
      this.removable = removable;
    }
  }

  public static class Result {
    public final List<RestoredPresentation> presentations;
    // What only the client can apply (open panels...), already checked.
    public final String clientLayoutJson;

    Result(List<RestoredPresentation> presentations, String clientLayoutJson) {
      this.presentations = presentations;
      this.clientLayoutJson = clientLayoutJson;
    }
  }

  // What the manifest says about one presentation, once checked.
  private static class SavedPresentation {
    int index;
    String entryName;
    String extension;
    String name;
    int pageCount;
    int currentPage;
    boolean current;
    boolean downloadable;
    boolean removable;
    Map<Integer, List<JsonObject>> annotations = new TreeMap<>();
  }

  // Counts the uncompressed bytes read from the archive, whatever the zip headers claim.
  private class Budget {
    private long remaining = maxUncompressedSize;

    void consume(long bytes) throws RoomStateException {
      remaining -= bytes;
      if (remaining < 0) {
        throw new RoomStateException("tooLarge", "archive expands to more than " + maxUncompressedSize + " bytes");
      }
    }
  }

  public long getMaxZipSize() {
    return maxZipSize;
  }

  public Result restore(File archive, String meetingId, String userId, String loadId) throws RoomStateException {
    if (archive.length() > maxZipSize) {
      throw new RoomStateException("tooLarge", "archive is larger than " + maxZipSize + " bytes");
    }

    List<File> createdDirectories = new ArrayList<>();
    try (ZipFile zip = new ZipFile(archive)) {
      if (zip.size() > maxEntries) {
        throw new RoomStateException("invalid", "archive has more than " + maxEntries + " entries");
      }

      Budget budget = new Budget();
      JsonObject manifest = readObject(zip, MANIFEST_ENTRY, MAX_MANIFEST_SIZE, budget, true);
      checkFormat(manifest);

      List<SavedPresentation> saved = readPresentations(zip, manifest, budget);
      JsonArray chat = readChat(zip, budget);
      JsonArray notes = readArray(zip, NOTES_ENTRY, budget);
      JsonObject layoutFile = readObject(zip, LAYOUT_ENTRY, maxJsonEntrySize, budget, false);
      JsonObject serverLayout = serverLayout(layoutFile);
      JsonObject clientLayout = clientLayout(layoutFile);
      boolean notesPinned = booleanOf(objectOf(manifest, "notes"), "pinned", false);

      // Everything is valid: store the presentations under their new ids.
      List<RestoredPresentation> presentations = new ArrayList<>();
      for (SavedPresentation presentation : saved) {
        String presentationId = Util.generatePresentationId(presentation.name);
        File directory = Util.createPresentationDir(meetingId, presentationDir, presentationId);
        if (directory == null) {
          throw new RoomStateException("failed", "could not create the directory of presentation " + presentationId);
        }
        createdDirectories.add(directory);

        File file = new File(directory, Util.createNewFilename(presentationId, presentation.extension));
        extract(zip, presentation.entryName, file, budget);
        if (!SupportedFileTypes.isPresentationMimeTypeValid(file, presentation.extension)) {
          throw new RoomStateException("invalidPresentation",
              "presentation " + presentation.index + " is not a supported " + presentation.extension + " file");
        }

        AnnotationRewriter.rewrite(presentation.annotations, presentationId, userId);
        presentations.add(new RestoredPresentation(presentationId, presentation.name, file,
            presentation.current, presentation.downloadable, presentation.removable));
      }

      // From here on the meeting changes.
      sendBegin(meetingId, userId, loadId, saved, presentations, serverLayout, notesPinned);
      if (chat != null) {
        JsonObject body = new JsonObject();
        body.addProperty("loadId", loadId);
        body.add("messages", chat);
        send("RestoreGroupChatMessagesSysMsg", meetingId, userId, body);
      }
      for (int i = 0; i < saved.size(); i++) {
        sendAnnotations(meetingId, userId, loadId, presentations.get(i).presentationId, saved.get(i).annotations);
      }
      if (notes != null) {
        JsonObject body = new JsonObject();
        body.addProperty("loadId", loadId);
        body.add("contentJson", notes);
        send("RestoreSharedNotesSysMsg", meetingId, userId, body);
      }
      JsonObject end = new JsonObject();
      end.addProperty("loadId", loadId);
      send("RestoreMeetingStateEndSysMsg", meetingId, userId, end);

      log.info("Room state {} loaded into meeting {} by {}: {} presentations, {} chat messages",
          loadId, meetingId, userId, presentations.size(), chat == null ? 0 : chat.size());
      return new Result(presentations, gson.toJson(clientLayout));
    } catch (RoomStateException e) {
      deleteQuietly(createdDirectories);
      throw e;
    } catch (IOException | RuntimeException e) {
      deleteQuietly(createdDirectories);
      throw new RoomStateException("invalid", "could not read the archive: " + e);
    }
  }

  private void checkFormat(JsonObject manifest) throws RoomStateException {
    if (!FORMAT.equals(stringOf(manifest, "format", ""))) {
      throw new RoomStateException("invalid", "not a room state archive");
    }
    if (intOf(manifest, "version", 0) != VERSION) {
      throw new RoomStateException("unsupportedVersion", "unsupported room state version");
    }
  }

  private List<SavedPresentation> readPresentations(ZipFile zip, JsonObject manifest, Budget budget)
      throws IOException, RoomStateException {
    List<SavedPresentation> presentations = new ArrayList<>();
    JsonElement list = manifest.get("presentations");
    if (list == null || !list.isJsonArray()) return presentations;

    if (list.getAsJsonArray().size() > maxPresentations) {
      throw new RoomStateException("tooManyPresentations", "more than " + maxPresentations + " presentations");
    }

    Set<Integer> indexes = new HashSet<>();
    for (JsonElement element : list.getAsJsonArray()) {
      if (!element.isJsonObject()) throw new RoomStateException("invalid", "malformed presentation entry");
      JsonObject entry = element.getAsJsonObject();

      SavedPresentation presentation = new SavedPresentation();
      presentation.index = intOf(entry, "index", -1);
      presentation.entryName = stringOf(entry, "file", "");
      Matcher matcher = PRESENTATION_FILE_PATTERN.matcher(presentation.entryName);
      if (!matcher.matches() || Integer.parseInt(matcher.group(1)) != presentation.index
          || !indexes.add(presentation.index)) {
        throw new RoomStateException("invalid", "unexpected presentation file name");
      }
      presentation.extension = matcher.group(2);
      presentation.name = presentationName(stringOf(entry, "name", ""), presentation);
      presentation.pageCount = intOf(entry, "pageCount", 0);
      if (presentation.pageCount < 1 || presentation.pageCount > MAX_PAGES) {
        throw new RoomStateException("invalid", "unexpected page count");
      }
      presentation.currentPage = Math.min(Math.max(intOf(entry, "currentPage", 1), 1), presentation.pageCount);
      presentation.current = booleanOf(entry, "current", false);
      presentation.downloadable = booleanOf(entry, "downloadable", false);
      presentation.removable = booleanOf(entry, "removable", true);
      readAnnotations(zip, presentation, budget);
      presentations.add(presentation);
    }

    // Exactly one presentation is shown.
    boolean currentFound = false;
    for (SavedPresentation presentation : presentations) {
      presentation.current = presentation.current && !currentFound;
      currentFound = currentFound || presentation.current;
    }
    if (!currentFound && !presentations.isEmpty()) presentations.get(0).current = true;

    return presentations;
  }

  // The name is shown to users and ends up in messages: keep the file name only.
  private String presentationName(String savedName, SavedPresentation presentation) {
    String name = FilenameUtils.getName(savedName).replaceAll("\\p{Cntrl}", "").trim();
    if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH);
    if (FilenameUtils.getBaseName(name).isEmpty()) name = "presentation-" + presentation.index;
    if (FilenameUtils.getExtension(name).isEmpty()) name = name + "." + presentation.extension;
    return name;
  }

  private void readAnnotations(ZipFile zip, SavedPresentation presentation, Budget budget)
      throws IOException, RoomStateException {
    String entryName = "presentations/" + presentation.index + "/annotations.json";
    JsonObject file = readObject(zip, entryName, maxJsonEntrySize, budget, false);
    JsonObject pages = objectOf(file, "pages");
    if (pages == null) return;

    int count = 0;
    for (Map.Entry<String, JsonElement> page : pages.entrySet()) {
      int pageNumber;
      try {
        pageNumber = Integer.parseInt(page.getKey());
      } catch (NumberFormatException e) {
        continue;
      }
      if (pageNumber < 1 || pageNumber > presentation.pageCount || !page.getValue().isJsonArray()) continue;

      List<JsonObject> annotations = new ArrayList<>();
      for (JsonElement element : page.getValue().getAsJsonArray()) {
        if (!element.isJsonObject()) continue;
        JsonObject saved = element.getAsJsonObject();
        String id = stringOf(saved, "id", "");
        JsonObject info = objectOf(saved, "annotationInfo");
        if (id.isEmpty() || id.length() > MAX_ID_LENGTH || info == null) continue;

        // Only the fields akka-apps expects; the rewriter fills in the others.
        JsonObject annotation = new JsonObject();
        annotation.addProperty("id", id);
        annotation.add("annotationInfo", info);
        annotations.add(annotation);
      }

      count += annotations.size();
      if (count > maxAnnotationsPerPresentation) {
        throw new RoomStateException("tooManyAnnotations",
            "more than " + maxAnnotationsPerPresentation + " annotations in presentation " + presentation.index);
      }
      if (!annotations.isEmpty()) presentation.annotations.put(pageNumber, annotations);
    }
  }

  private JsonArray readChat(ZipFile zip, Budget budget) throws IOException, RoomStateException {
    JsonArray saved = readArray(zip, CHAT_ENTRY, budget);
    if (saved == null) return null;
    if (saved.size() > maxChatMessages) {
      throw new RoomStateException("tooManyChatMessages", "more than " + maxChatMessages + " chat messages");
    }

    JsonArray messages = new JsonArray();
    for (JsonElement element : saved) {
      if (!element.isJsonObject()) continue;
      JsonObject savedMessage = element.getAsJsonObject();
      String id = stringOf(savedMessage, "id", "");
      String text = stringOf(savedMessage, "message", "");
      if (id.isEmpty() || id.length() > MAX_ID_LENGTH || text.length() > MAX_CHAT_MESSAGE_LENGTH) continue;

      String role = stringOf(savedMessage, "senderRole", "VIEWER");
      JsonObject metadata = objectOf(savedMessage, "metadata");

      JsonObject message = new JsonObject();
      message.addProperty("id", id);
      message.addProperty("timestamp", Math.max(longOf(savedMessage, "timestamp", 0), 0));
      message.addProperty("senderId", limit(stringOf(savedMessage, "senderId", ""), MAX_ID_LENGTH));
      message.addProperty("senderName", limit(stringOf(savedMessage, "senderName", ""), MAX_NAME_LENGTH));
      message.addProperty("senderRole", "MODERATOR".equals(role) ? "MODERATOR" : "VIEWER");
      message.addProperty("chatEmphasizedText", booleanOf(savedMessage, "chatEmphasizedText", false));
      message.addProperty("message", text);
      message.addProperty("replyToMessageId", limit(stringOf(savedMessage, "replyToMessageId", ""), MAX_ID_LENGTH));
      message.addProperty("messageType", limit(stringOf(savedMessage, "messageType", "default"), 64));
      message.add("metadata", metadata == null ? new JsonObject() : metadata);
      messages.add(message);
    }
    return messages;
  }

  // The layout kept by akka-apps. An empty layout name means "leave the layout alone".
  private JsonObject serverLayout(JsonObject layoutFile) {
    JsonObject saved = objectOf(layoutFile, "server");
    JsonObject layout = new JsonObject();
    layout.addProperty("currentLayout", simpleValue(saved, "currentLayout"));
    layout.addProperty("pushLayout", booleanOf(saved, "pushLayout", false));
    layout.addProperty("presentationIsOpen", booleanOf(saved, "presentationIsOpen", true));
    layout.addProperty("cameraPosition", simpleValue(saved, "cameraPosition"));
    layout.addProperty("focusedCamera", "none");
    layout.addProperty("presentationVideoRate", doubleOf(saved, "presentationVideoRate", 0));
    layout.addProperty("screenshareAsContent", booleanOf(saved, "screenshareAsContent", false));
    return layout;
  }

  // Only known flags and short plain values reach the client.
  private JsonObject clientLayout(JsonObject layoutFile) {
    JsonObject saved = objectOf(layoutFile, "client");
    JsonObject layout = new JsonObject();
    if (saved == null) return layout;
    for (String flag : CLIENT_LAYOUT_FLAGS) {
      if (saved.has(flag)) layout.addProperty(flag, booleanOf(saved, flag, false));
    }
    for (String value : CLIENT_LAYOUT_VALUES) {
      if (saved.has(value)) layout.addProperty(value, simpleValue(saved, value));
    }
    return layout;
  }

  private void sendBegin(String meetingId, String userId, String loadId, List<SavedPresentation> saved,
                         List<RestoredPresentation> presentations, JsonObject layout, boolean notesPinned) {
    JsonArray list = new JsonArray();
    for (int i = 0; i < saved.size(); i++) {
      JsonObject presentation = new JsonObject();
      presentation.addProperty("presentationId", presentations.get(i).presentationId);
      presentation.addProperty("current", saved.get(i).current);
      presentation.addProperty("currentPage", saved.get(i).currentPage);
      list.add(presentation);
    }

    JsonObject body = new JsonObject();
    body.addProperty("loadId", loadId);
    body.add("presentations", list);
    body.add("layout", layout);
    body.addProperty("notesPinned", notesPinned);
    send("RestoreMeetingStateBeginSysMsg", meetingId, userId, body);
  }

  private void sendAnnotations(String meetingId, String userId, String loadId, String presentationId,
                               Map<Integer, List<JsonObject>> annotationsByPage) {
    for (Map.Entry<Integer, List<JsonObject>> page : annotationsByPage.entrySet()) {
      List<JsonObject> annotations = page.getValue();
      for (int from = 0; from < annotations.size(); from += ANNOTATIONS_PER_MESSAGE) {
        JsonArray batch = new JsonArray();
        for (JsonObject annotation : annotations.subList(from, Math.min(from + ANNOTATIONS_PER_MESSAGE, annotations.size()))) {
          batch.add(annotation);
        }

        JsonObject body = new JsonObject();
        body.addProperty("loadId", loadId);
        body.addProperty("presentationId", presentationId);
        body.addProperty("whiteboardId", presentationId + "/" + page.getKey());
        body.add("annotations", batch);
        send("RestoreWhiteboardAnnotationsSysMsg", meetingId, userId, body);
      }
    }
  }

  private void send(String name, String meetingId, String userId, JsonObject body) {
    JsonObject routing = new JsonObject();
    routing.addProperty("sender", "bbb-web");

    JsonObject envelope = new JsonObject();
    envelope.addProperty("name", name);
    envelope.add("routing", routing);
    envelope.addProperty("timestamp", System.currentTimeMillis());

    JsonObject header = new JsonObject();
    header.addProperty("name", name);
    header.addProperty("meetingId", meetingId);
    header.addProperty("userId", userId);

    JsonObject core = new JsonObject();
    core.add("header", header);
    core.add("body", body);

    JsonObject message = new JsonObject();
    message.add("envelope", envelope);
    message.add("core", core);
    gw.send(toAkkaAppsChannel, gson.toJson(message));
  }

  private JsonObject readObject(ZipFile zip, String entryName, long limit, Budget budget, boolean required)
      throws IOException, RoomStateException {
    JsonElement element = readJson(zip, entryName, limit, budget);
    if (element == null) {
      if (required) throw new RoomStateException("invalid", entryName + " is missing");
      return null;
    }
    if (!element.isJsonObject()) throw new RoomStateException("invalid", entryName + " is not an object");
    return element.getAsJsonObject();
  }

  private JsonArray readArray(ZipFile zip, String entryName, Budget budget) throws IOException, RoomStateException {
    JsonElement element = readJson(zip, entryName, maxJsonEntrySize, budget);
    if (element == null) return null;
    if (!element.isJsonArray()) throw new RoomStateException("invalid", entryName + " is not a list");
    return element.getAsJsonArray();
  }

  private JsonElement readJson(ZipFile zip, String entryName, long limit, Budget budget)
      throws IOException, RoomStateException {
    ZipEntry entry = zip.getEntry(entryName);
    if (entry == null || entry.isDirectory()) return null;

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (InputStream in = zip.getInputStream(entry)) {
      copy(in, bytes, limit, budget, entryName);
    }

    byte[] json = bytes.toByteArray();
    if (isNestedTooDeep(json)) throw new RoomStateException("invalid", entryName + " is nested too deep");
    try {
      return JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
    } catch (JsonParseException e) {
      throw new RoomStateException("invalid", entryName + " is not valid JSON");
    }
  }

  // The JSON is later walked recursively: refuse documents made to exhaust the stack.
  static boolean isNestedTooDeep(byte[] json) {
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (byte b : json) {
      if (inString) {
        if (escaped) escaped = false;
        else if (b == '\\') escaped = true;
        else if (b == '"') inString = false;
      } else if (b == '"') {
        inString = true;
      } else if (b == '{' || b == '[') {
        if (++depth > MAX_JSON_DEPTH) return true;
      } else if (b == '}' || b == ']') {
        depth--;
      }
    }
    return false;
  }

  private void extract(ZipFile zip, String entryName, File target, Budget budget)
      throws IOException, RoomStateException {
    ZipEntry entry = zip.getEntry(entryName);
    if (entry == null || entry.isDirectory()) {
      throw new RoomStateException("invalid", entryName + " is missing");
    }
    try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(target)) {
      copy(in, out, maxPresentationFileSize, budget, entryName);
    }
  }

  private void copy(InputStream in, OutputStream out, long limit, Budget budget, String entryName)
      throws IOException, RoomStateException {
    byte[] buffer = new byte[64 * 1024];
    long total = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
      total += read;
      if (total > limit) {
        throw new RoomStateException("tooLarge", entryName + " is larger than " + limit + " bytes");
      }
      budget.consume(read);
      out.write(buffer, 0, read);
    }
  }

  private void deleteQuietly(List<File> directories) {
    for (File directory : directories) {
      FileUtils.deleteQuietly(directory);
    }
  }

  private static JsonObject objectOf(JsonObject parent, String name) {
    if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) return null;
    return parent.getAsJsonObject(name);
  }

  private static String stringOf(JsonObject parent, String name, String fallback) {
    if (parent == null || !parent.has(name) || !parent.get(name).isJsonPrimitive()
        || !parent.getAsJsonPrimitive(name).isString()) return fallback;
    return parent.get(name).getAsString();
  }

  private static boolean booleanOf(JsonObject parent, String name, boolean fallback) {
    if (parent == null || !parent.has(name) || !parent.get(name).isJsonPrimitive()
        || !parent.getAsJsonPrimitive(name).isBoolean()) return fallback;
    return parent.get(name).getAsBoolean();
  }

  private static double doubleOf(JsonObject parent, String name, double fallback) {
    if (parent == null || !parent.has(name) || !parent.get(name).isJsonPrimitive()
        || !parent.getAsJsonPrimitive(name).isNumber()) return fallback;
    double value = parent.get(name).getAsDouble();
    return Double.isFinite(value) ? value : fallback;
  }

  private static long longOf(JsonObject parent, String name, long fallback) {
    double value = doubleOf(parent, name, fallback);
    return value == Math.rint(value) && Math.abs(value) < 1e15 ? (long) value : fallback;
  }

  private static int intOf(JsonObject parent, String name, int fallback) {
    long value = longOf(parent, name, fallback);
    return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (int) value : fallback;
  }

  private static String simpleValue(JsonObject parent, String name) {
    String value = stringOf(parent, name, "");
    return SIMPLE_VALUE_PATTERN.matcher(value).matches() ? value : "";
  }

  private static String limit(String value, int maxLength) {
    return value.length() > maxLength ? value.substring(0, maxLength) : value;
  }

  public void setGw(IBbbWebApiGWApp gw) {
    this.gw = gw;
  }

  public void setPresentationDir(String presentationDir) {
    this.presentationDir = presentationDir;
  }

  public void setToAkkaAppsChannel(String toAkkaAppsChannel) {
    this.toAkkaAppsChannel = toAkkaAppsChannel;
  }

  public void setMaxZipSize(long maxZipSize) {
    this.maxZipSize = maxZipSize;
  }

  public void setMaxUncompressedSize(long maxUncompressedSize) {
    this.maxUncompressedSize = maxUncompressedSize;
  }

  public void setMaxEntries(int maxEntries) {
    this.maxEntries = maxEntries;
  }

  public void setMaxJsonEntrySize(long maxJsonEntrySize) {
    this.maxJsonEntrySize = maxJsonEntrySize;
  }

  public void setMaxPresentationFileSize(long maxPresentationFileSize) {
    this.maxPresentationFileSize = maxPresentationFileSize;
  }

  public void setMaxPresentations(int maxPresentations) {
    this.maxPresentations = maxPresentations;
  }

  public void setMaxChatMessages(int maxChatMessages) {
    this.maxChatMessages = maxChatMessages;
  }

  public void setMaxAnnotationsPerPresentation(int maxAnnotationsPerPresentation) {
    this.maxAnnotationsPerPresentation = maxAnnotationsPerPresentation;
  }
}
