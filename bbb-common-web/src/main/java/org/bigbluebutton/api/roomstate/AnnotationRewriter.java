package org.bigbluebutton.api.roomstate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prepares saved whiteboard annotations for the presentation they are loaded
 * into. A presentation gets a new id on every upload and annotation ids are
 * unique across meetings, so every annotation gets a new id, references between
 * shapes (a shape's parent, the shapes an arrow is bound to) follow, and the
 * annotation is attached to the new presentation.
 */
public final class AnnotationRewriter {
  private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final int ID_LENGTH = 21;
  private static final SecureRandom RANDOM = new SecureRandom();

  private AnnotationRewriter() {
  }

  static String newShapeId() {
    StringBuilder id = new StringBuilder("shape:");
    for (int i = 0; i < ID_LENGTH; i++) {
      id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
    }
    return id.toString();
  }

  /**
   * @param annotationsByPage saved annotations ({id, annotationInfo, ...}) per page number, changed in place
   * @param presentationId    id of the presentation the annotations are loaded into
   * @param userId            the user loading the state, who becomes their author
   */
  public static void rewrite(Map<Integer, List<JsonObject>> annotationsByPage, String presentationId, String userId) {
    // Shapes can reference shapes of the same page only, but mapping the whole
    // presentation at once costs nothing and cannot map an id twice.
    Map<String, String> newIds = new HashMap<>();
    for (List<JsonObject> annotations : annotationsByPage.values()) {
      for (JsonObject annotation : annotations) {
        newIds.put(annotation.get("id").getAsString(), newShapeId());
      }
    }

    for (Map.Entry<Integer, List<JsonObject>> page : annotationsByPage.entrySet()) {
      String whiteboardId = presentationId + "/" + page.getKey();
      for (JsonObject annotation : page.getValue()) {
        JsonObject info = replaceIds(annotation.getAsJsonObject("annotationInfo"), newIds).getAsJsonObject();

        JsonObject meta = info.has("meta") && info.get("meta").isJsonObject()
            ? info.getAsJsonObject("meta") : new JsonObject();
        meta.addProperty("presentationId", presentationId);
        meta.addProperty("createdBy", userId);
        if (meta.has("updatedBy")) meta.addProperty("updatedBy", userId);
        info.add("meta", meta);

        annotation.addProperty("id", newIds.get(annotation.get("id").getAsString()));
        annotation.add("annotationInfo", info);
        annotation.addProperty("wbId", whiteboardId);
        annotation.addProperty("userId", userId);
      }
    }
  }

  // Returns a copy in which every string that is the id of a saved annotation
  // is replaced by its new id.
  private static JsonElement replaceIds(JsonElement element, Map<String, String> newIds) {
    if (element.isJsonObject()) {
      JsonObject copy = new JsonObject();
      for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
        copy.add(entry.getKey(), replaceIds(entry.getValue(), newIds));
      }
      return copy;
    }

    if (element.isJsonArray()) {
      JsonArray copy = new JsonArray();
      for (JsonElement item : element.getAsJsonArray()) {
        copy.add(replaceIds(item, newIds));
      }
      return copy;
    }

    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      String newId = newIds.get(element.getAsString());
      if (newId != null) return new JsonPrimitive(newId);
    }

    return element;
  }
}
