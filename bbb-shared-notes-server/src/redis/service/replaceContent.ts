import * as Y from "yjs";
import { createServerEditor } from "../../blocknote/editor";
import hocuspocus from "../../hocuspocus";
import { Logger } from "../../common/logger";

const logger = new Logger('redis.service.replaceContent');

/**
 * Replaces the whole content of a shared notes document with the given BlockNote
 * blocks (as exported by the JSON export). Used when a saved room state is loaded
 * into a meeting; users who have the notes open see the new content right away.
 *
 * An empty list of blocks empties the notes.
 *
 * @param user the user who asked for it
 */
export async function replaceContent(
  padId: string,
  blocks: unknown,
  user: { intUserId: string; meetingId: string },
): Promise<{ statusCode: string; error?: string; }> {
  if (!Array.isArray(blocks)) {
    return { statusCode: 'invalid_content', error: 'Content is not a list of blocks' };
  }

  let connection: Awaited<ReturnType<typeof hocuspocus.openDirectConnection>> | null = null;
  try {
    const editor = createServerEditor();

    // Convert the blocks once into a throw-away document: malformed blocks fail
    // here, before the notes of the meeting have been touched.
    if (blocks.length > 0) {
      editor.blocksToYXmlFragment(blocks, new Y.Doc().getXmlFragment("doc"));
    }

    // The change is attributed to the user who loaded the state, as an edit of
    // theirs would be, so that the other users are told the notes changed.
    connection = await hocuspocus.openDirectConnection(padId, { user });
    await connection.transact((doc) => {
      const fragment = doc.getXmlFragment("doc");
      if (fragment.length > 0) fragment.delete(0, fragment.length);
      if (blocks.length > 0) editor.blocksToYXmlFragment(blocks, fragment);
    });

    logger.info('Document content replaced', { documentName: padId, blocks: blocks.length });
    return { statusCode: 'document_replaced' };
  } catch (error) {
    logger.error('Error replacing document content', { error, documentName: padId });
    return {
      statusCode: 'unknown_error',
      error: error instanceof Error ? error.message : 'Unknown error',
    };
  } finally {
    if (connection) await connection.disconnect();
  }
}
