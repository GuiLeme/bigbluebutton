package org.bigbluebutton.api.roomstate;

/**
 * A room state archive that cannot be loaded. The key is reported to the
 * client, which shows a matching message; the detail only goes to the log.
 */
public class RoomStateException extends Exception {
  private final String key;

  public RoomStateException(String key, String detail) {
    super(detail);
    this.key = key;
  }

  public String getKey() {
    return key;
  }
}
