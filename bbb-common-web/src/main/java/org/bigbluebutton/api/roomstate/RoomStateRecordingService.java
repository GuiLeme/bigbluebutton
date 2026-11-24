package org.bigbluebutton.api.roomstate;

import org.apache.commons.io.FileUtils;
import org.bigbluebutton.api.Util;
import org.bigbluebutton.api.domain.Meeting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Room states kept on the server.
 *
 * While a meeting runs, the state its moderator saved last is kept with the
 * meeting's files (written by bbb-export-annotations). When a meeting created
 * with record=true ends, that state is published as a recording format named
 * "state" under the meeting's record id: getRecordings lists it, and
 * publishing, unpublishing and deleting the recording apply to it as to any
 * other format.
 *
 * A later meeting can load the states whose record ids were given when it was
 * created (create parameter roomStateRecordIDs).
 */
public class RoomStateRecordingService {
  private static final Logger log = LoggerFactory.getLogger(RoomStateRecordingService.class);

  public static final String FORMAT = "state";
  // Names the state saved in the running meeting, next to the record ids of earlier ones.
  public static final String CURRENT = "current";
  public static final String ARCHIVE_NAME = "room-state.zip";
  private static final String METADATA_NAME = "metadata.xml";
  private static final String STATE_DIRECTORY = "room-state";
  private static final Pattern XML_NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]*$");

  private String presentationDir;
  private String publishedDir;
  private String serverUrl;

  /** A saved state a moderator can load. */
  public static class SavedState {
    public final String id;
    public final String name;
    public final long savedAt;

    SavedState(String id, String name, long savedAt) {
      this.id = id;
      this.name = name;
      this.savedAt = savedAt;
    }
  }

  /** The state saved last in a running meeting, or null when there is none. */
  public File currentArchive(String meetingId) {
    if (!Util.isMeetingIdValidFormat(meetingId)) return null;
    File meetingDirectory = new File(new File(presentationDir, meetingId), meetingId);
    File archive = new File(new File(new File(meetingDirectory, STATE_DIRECTORY), CURRENT), ARCHIVE_NAME);
    return archive.isFile() ? archive : null;
  }

  /** The published state of a recording, or null when there is none. */
  public File publishedArchive(String recordId) {
    if (recordId == null || !Util.isMeetingIdValidFormat(recordId)) return null;
    File archive = new File(publishedDirectory(recordId), ARCHIVE_NAME);
    return archive.isFile() ? archive : null;
  }

  /**
   * The archive of a state the moderators of a meeting may load: the one saved
   * in the meeting itself, or a published one the meeting was created with.
   */
  public File archiveOf(Meeting meeting, String id) {
    if (CURRENT.equals(id)) return currentArchive(meeting.getInternalId());
    if (id == null || !meeting.getRoomStateRecordIds().contains(id)) return null;
    return publishedArchive(id);
  }

  /** What the Load dialog of a meeting offers, the most recent first. */
  public List<SavedState> list(Meeting meeting) {
    List<SavedState> states = new ArrayList<>();
    for (String recordId : meeting.getRoomStateRecordIds()) {
      if (publishedArchive(recordId) == null) continue;
      try {
        states.add(readPublished(recordId));
      } catch (Exception e) {
        log.warn("Ignoring room state {} with unreadable metadata: {}", recordId, e.toString());
      }
    }
    states.sort(Comparator.comparingLong((SavedState state) -> state.savedAt).reversed());

    File current = currentArchive(meeting.getInternalId());
    if (current != null) states.add(0, new SavedState(CURRENT, meeting.getName(), current.lastModified()));
    return states;
  }

  /**
   * Publishes the state saved in a meeting that is ending as its "state"
   * recording. Returns true when one was published.
   */
  public boolean publish(Meeting meeting) {
    String meetingId = meeting.getInternalId();
    File archive = currentArchive(meetingId);
    if (!meeting.isRecord() || archive == null) return false;

    File target = publishedDirectory(meetingId);
    File staging = new File(target.getParentFile(), "." + meetingId + ".tmp");
    try {
      FileUtils.deleteDirectory(staging);
      if (!staging.mkdirs()) throw new IllegalStateException("could not create " + staging);
      File staged = new File(staging, ARCHIVE_NAME);
      Files.copy(archive.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING);
      writeMetadata(meeting, new File(staging, METADATA_NAME), archive.lastModified(), staged.length());

      FileUtils.deleteDirectory(target);
      Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
      log.info("Published the room state of meeting {} as a recording", meetingId);
      return true;
    } catch (Exception e) {
      log.error("Could not publish the room state of meeting {}", meetingId, e);
      FileUtils.deleteQuietly(staging);
      return false;
    }
  }

  private File publishedDirectory(String recordId) {
    return new File(new File(publishedDir, FORMAT), recordId);
  }

  private SavedState readPublished(String recordId) throws Exception {
    Document document = newDocumentBuilder().parse(new File(publishedDirectory(recordId), METADATA_NAME));
    Element recording = document.getDocumentElement();

    String name = recordId;
    NodeList meetings = recording.getElementsByTagName("meeting");
    if (meetings.getLength() > 0) {
      String meetingName = ((Element) meetings.item(0)).getAttribute("name");
      if (!meetingName.isEmpty()) name = meetingName;
    }

    long savedAt = 0;
    NodeList endTimes = recording.getElementsByTagName("end_time");
    if (endTimes.getLength() > 0) savedAt = Long.parseLong(endTimes.item(0).getTextContent().trim());

    return new SavedState(recordId, name, savedAt);
  }

  // Same shape as the metadata.xml the recording scripts write for the other formats.
  private void writeMetadata(Meeting meeting, File file, long savedAt, long size) throws Exception {
    String meetingId = meeting.getInternalId();
    Document document = newDocumentBuilder().newDocument();
    Element recording = document.createElement("recording");
    document.appendChild(recording);

    append(document, recording, "id", meetingId);
    append(document, recording, "state", "published");
    append(document, recording, "published", "true");
    append(document, recording, "start_time", String.valueOf(meeting.getCreateTime()));
    append(document, recording, "end_time", String.valueOf(savedAt));
    append(document, recording, "participants", String.valueOf(meeting.getNumUsers()));

    Element meetingElement = document.createElement("meeting");
    meetingElement.setAttribute("id", meetingId);
    meetingElement.setAttribute("externalId", meeting.getExternalId());
    meetingElement.setAttribute("name", meeting.getName());
    meetingElement.setAttribute("breakout", String.valueOf(Boolean.TRUE.equals(meeting.isBreakout())));
    recording.appendChild(meetingElement);

    Element meta = document.createElement("meta");
    append(document, meta, "isBreakout", String.valueOf(Boolean.TRUE.equals(meeting.isBreakout())));
    append(document, meta, "meetingId", meeting.getExternalId());
    append(document, meta, "meetingName", meeting.getName());
    for (Map.Entry<String, String> entry : meeting.getMetadata().entrySet()) {
      if (XML_NAME_PATTERN.matcher(entry.getKey()).matches() && meta.getElementsByTagName(entry.getKey()).getLength() == 0) {
        append(document, meta, entry.getKey(), entry.getValue());
      }
    }
    recording.appendChild(meta);

    Element playback = document.createElement("playback");
    append(document, playback, "format", FORMAT);
    append(document, playback, "link", serverUrl + "/bigbluebutton/api/getRoomState?recordID=" + meetingId);
    append(document, playback, "processing_time", "0");
    append(document, playback, "duration", "0");
    append(document, playback, "size", String.valueOf(size));
    recording.appendChild(playback);

    TransformerFactory factory = TransformerFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    Transformer transformer = factory.newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.transform(new DOMSource(document), new StreamResult(file));
  }

  private static void append(Document document, Element parent, String name, String value) {
    Element element = document.createElement(name);
    element.setTextContent(value == null ? "" : value);
    parent.appendChild(element);
  }

  private static DocumentBuilder newDocumentBuilder() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory.newDocumentBuilder();
  }

  public void setPresentationDir(String presentationDir) {
    this.presentationDir = presentationDir;
  }

  public void setPublishedDir(String publishedDir) {
    this.publishedDir = publishedDir;
  }

  public void setServerUrl(String serverUrl) {
    this.serverUrl = serverUrl;
  }
}
