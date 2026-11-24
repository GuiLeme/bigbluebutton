import Logger from '../lib/utils/logger.js';
import axios from 'axios';
import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import redis from 'redis';
import yazl from 'yazl';
import {workerData} from 'worker_threads';

/**
 * Builds the room state archive for a 'MeetingStateSaveJob'.
 *
 * akka-apps stores the collected state (chat, presentations with their
 * annotations, layout) under the redis key `meetingState:<jobId>`. This worker
 * adds the original presentation files and the shared notes, and writes
 * everything into a single zip: the state saved in the meeting, which takes the
 * place of the one saved before. bbb-web loads it back on request, and
 * publishes it with the recording when the meeting ends.
 *
 * A job with `download` set is for the moderator to download: its archive stays
 * in the directory of the job, where bbb-web serves it from, and the state
 * saved in the meeting is left as it is.
 */

const jobId = workerData.jobId;
const logger = new Logger('roomState Saver');
const config = JSON.parse(fs.readFileSync('./config/settings.json', 'utf8'));
const settings = config.roomState;

const FORMAT = 'bbb-room-state';
const FORMAT_VERSION = 1;
const SAFE_ID = /^[A-Za-z0-9_-]+$/;
const SAFE_EXTENSION = /^[a-z0-9]{1,5}$/;

const dropbox = path.join(config.shared.presAnnDropboxDir, jobId);
const job = JSON.parse(fs.readFileSync(path.join(dropbox, 'job')));

const sha256 = (file) => new Promise((resolve, reject) => {
  const hash = crypto.createHash('sha256');
  fs.createReadStream(file)
      .on('error', reject)
      .on('data', (chunk) => hash.update(chunk))
      .on('end', () => resolve(hash.digest('hex')));
});

/**
 * Finds the file the user uploaded for a presentation. bbb-web stores it as
 * `<presId>.<extension>` next to the converted PDF.
 * @param {Object} pres - presentation entry from the collected state
 * @return {String|null} absolute path, or null when no file is found
 */
function findOriginalFile(pres) {
  if (!SAFE_ID.test(pres.sourceId) || !fs.existsSync(pres.presLocation)) {
    return null;
  }

  const prefix = `${pres.sourceId}.`;
  const candidates = fs.readdirSync(pres.presLocation, {withFileTypes: true})
      .filter((entry) => entry.isFile() && entry.name.startsWith(prefix))
      .map((entry) => entry.name)
      .filter((name) => SAFE_EXTENSION.test(name.slice(prefix.length)));

  const nameExtension = path.extname(pres.name).slice(1).toLowerCase();
  const preferred = [nameExtension, 'pdf'];
  for (const extension of preferred) {
    const match = candidates.find(
        (name) => name.slice(prefix.length) === extension);
    if (match) return path.join(pres.presLocation, match);
  }

  return candidates.length > 0 ?
    path.join(pres.presLocation, candidates[0]) : null;
}

/**
 * Fetches the shared notes of the meeting as BlockNote blocks.
 * @param {String} meetingId - internal meeting id
 * @return {Promise<Array>} the blocks
 */
async function fetchNotes(meetingId) {
  const url = `${settings.sharedNotesAPI}/loopback/api/documents/` +
    `bn-document__${meetingId}/export/json`;
  const response = await axios.get(url, {
    timeout: settings.sharedNotesTimeoutMs,
    responseType: 'text',
    transformResponse: (data) => data,
  });
  const blocks = JSON.parse(response.data);
  if (!Array.isArray(blocks)) {
    throw new Error('shared notes export is not a list of blocks');
  }
  return blocks;
}

/**
 * Reads the state akka-apps collected for this job, and removes it from redis.
 * @return {Promise<Object>} the collected state
 */
async function takeStateFromRedis() {
  const client = redis.createClient({
    password: config.redis.password,
    socket: {
      host: config.redis.host,
      port: config.redis.port,
    },
  });
  client.on('error', (err) => logger.info('Redis Client Error', err));
  await client.connect();

  try {
    const key = `meetingState:${jobId}`;
    const stateJson = await client.get(key);
    await client.del(key);
    if (!stateJson) throw new Error(`no state stored for job ${jobId}`);
    return JSON.parse(stateJson);
  } finally {
    await client.disconnect();
  }
}

/**
 * Builds the archive.
 * @param {String} jobDir - directory of this job, where the archive is built
 * @param {String} targetDir - directory the finished archive goes to
 */
async function save(jobDir, targetDir) {
  const state = await takeStateFromRedis();
  const warnings = [];
  const zip = new yazl.ZipFile();
  const addJson = (name, value) =>
    zip.addBuffer(Buffer.from(JSON.stringify(value, null, 2)), name);

  let totalBytes = 0;
  const presentations = [];
  for (const pres of state.presentations) {
    const index = presentations.length + 1;
    const original = findOriginalFile(pres);
    if (!original) {
      warnings.push(`Presentation "${pres.name}" skipped: file not found`);
      continue;
    }

    const sizeBytes = fs.statSync(original).size;
    totalBytes += sizeBytes;
    if (totalBytes > settings.maxPresentationsBytes) {
      throw new Error('presentations exceed the maximum archive size');
    }

    const extension = path.extname(original).slice(1);
    const file = `presentations/${index}/original.${extension}`;
    const annotationsFile = `presentations/${index}/annotations.json`;

    const pages = {};
    let annotationCount = 0;
    for (const page of pres.pages) {
      if (page.annotations.length > 0) {
        pages[page.num] = page.annotations;
        annotationCount += page.annotations.length;
      }
    }

    zip.addFile(original, file);
    addJson(annotationsFile, {pages});

    presentations.push({
      index,
      sourceId: pres.sourceId,
      name: pres.name,
      file,
      sizeBytes,
      sha256: await sha256(original),
      current: pres.current,
      currentPage: pres.currentPage,
      pageCount: pres.pageCount,
      downloadable: pres.downloadable,
      removable: pres.removable,
      default: pres.default,
      annotations: annotationsFile,
      annotationCount,
      pages: pres.pages.map((page) => ({
        num: page.num,
        infiniteWhiteboard: page.infiniteWhiteboard,
      })),
    });
  }

  // Exactly one presentation is shown when the archive is loaded.
  if (presentations.length > 0 && !presentations.some((p) => p.current)) {
    presentations[0].current = true;
  }

  let notes = [];
  try {
    notes = await fetchNotes(job.meetingId);
  } catch (error) {
    logger.error(`Job ${jobId}: could not export shared notes: ` +
      error.message);
    warnings.push('Shared notes could not be exported');
  }

  const client = state.layout.client || {};
  addJson('chat/public.json', state.chat);
  addJson('notes/notes.json', notes);
  addJson('layout.json', state.layout);
  addJson('manifest.json', {
    format: FORMAT,
    version: FORMAT_VERSION,
    savedAt: state.savedAt,
    source: state.source,
    chat: {file: 'chat/public.json', count: state.chat.length},
    presentations,
    notes: {
      file: 'notes/notes.json',
      format: 'blocknote-json',
      pinned: client.notesPinned === true,
    },
    layout: {file: 'layout.json'},
    warnings,
  });

  const target = path.join(targetDir, settings.archiveName);
  const partial = path.join(jobDir, `${settings.archiveName}.tmp`);
  await new Promise((resolve, reject) => {
    const output = fs.createWriteStream(partial);
    output.on('error', reject);
    output.on('close', resolve);
    zip.outputStream.on('error', reject);
    zip.outputStream.pipe(output);
    zip.end();
  });

  // The rename replaces the state saved before in one step: a load or the end
  // of the meeting sees either the previous archive or this one, whole.
  fs.mkdirSync(targetDir, {recursive: true});
  fs.renameSync(partial, target);
  // bbb-web reports the job as done once this marker exists.
  fs.writeFileSync(path.join(jobDir, 'ready'), '');
  logger.info(`Job ${jobId}: room state saved to ${target} ` +
    `(${presentations.length} presentations, ${state.chat.length} messages)`);
}

/** Runs the job and leaves a marker bbb-web can report when it fails. */
async function run() {
  if (!SAFE_ID.test(jobId) || !SAFE_ID.test(job.meetingId)) {
    logger.error(`Refusing room state job with unsafe ids: ${jobId}`);
    return;
  }

  const stateDir = path.join(
      settings.presentationDir, job.meetingId, job.meetingId, 'room-state');
  const outputDir = path.join(stateDir, jobId);

  try {
    fs.mkdirSync(outputDir, {recursive: true});
    const targetDir = job.download === true ?
      outputDir : path.join(stateDir, 'current');
    await save(outputDir, targetDir);
  } catch (error) {
    logger.error(`Job ${jobId}: saving room state failed: ${error.stack}`);
    try {
      fs.rmSync(path.join(outputDir, `${settings.archiveName}.tmp`),
          {force: true});
      fs.writeFileSync(path.join(outputDir, 'error.json'),
          JSON.stringify({error: 'save_failed'}));
    } catch (markerError) {
      logger.error(`Job ${jobId}: could not write error marker: ` +
        markerError.message);
    }
  } finally {
    fs.rmSync(dropbox, {recursive: true, force: true});
  }
}

run();
