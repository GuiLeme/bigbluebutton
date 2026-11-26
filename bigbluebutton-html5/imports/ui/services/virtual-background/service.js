import deviceInfo from '/imports/utils/deviceInfo';
import browserInfo from '/imports/utils/browserInfo';
import { createVirtualBackgroundService } from '/imports/ui/services/virtual-background';
import { getStorageSingletonInstance } from '/imports/ui/services/storage';

const BLUR_FILENAME = 'blur.jpg';
const EFFECT_TYPES = {
  BLUR_TYPE: 'blur',
  IMAGE_TYPE: 'image',
  NONE_TYPE: 'none',
};

const MODELS = {
  model96: {
    path: '/resources/tfmodels/segm_lite_v681.tflite',
    segmentationDimensions: {
      height: 96,
      width: 160,
    },
  },
  model144: {
    path: '/resources/tfmodels/segm_full_v679.tflite',
    segmentationDimensions: {
      height: 144,
      width: 256,
    },
  },
};

const getBasePath = () => {
  const BASE_PATH = window.meetingClientSettings.public.app.cdn
    + window.meetingClientSettings.public.app.basename;

  return BASE_PATH;
};

const getThumbnailsPath = () => {
  const {
    thumbnailsPath: THUMBNAILS_PATH = '/resources/images/virtual-backgrounds/thumbnails/',
  } = window.meetingClientSettings.public.virtualBackgrounds;

  return THUMBNAILS_PATH;
};

const getImageNames = () => {
  const {
    fileNames: IMAGE_NAMES = ['home.jpg', 'coffeeshop.jpg', 'board.jpg'],
  } = window.meetingClientSettings.public.virtualBackgrounds;

  return IMAGE_NAMES;
};

const getIsStoredOnBBB = () => {
  const {
    storedOnBBB: IS_STORED_ON_BBB = true,
  } = window.meetingClientSettings.public.virtualBackgrounds;

  return IS_STORED_ON_BBB;
};

const createVirtualBackgroundStream = (type, name, isVirtualBackground, stream, customParams) => {
  const buildParams = {
    backgroundType: type,
    backgroundFilename: name,
    isVirtualBackground,
    customParams,
  };

  return createVirtualBackgroundService(buildParams).then((service) => {
    const effect = service.startEffect(stream);
    return { service, effect };
  });
};

const getVirtualBackgroundThumbnail = (name) => {
  if (name === BLUR_FILENAME) {
    return `${getBasePath()}/resources/images/virtual-backgrounds/thumbnails/${name}`;
  }

  return (getIsStoredOnBBB() ? getBasePath() : '') + getThumbnailsPath() + name;
};

// Stores the last chosen camera effect into the session storage in the following format:
// {
//   type: <EFFECT_TYPES>,
//   name: effect filename, if any
// }
const setSessionVirtualBackgroundInfo = (deviceId, type, name, uniqueId = null) => {
  getStorageSingletonInstance().setItem(`VirtualBackgroundInfo_${deviceId}`, { type, name, uniqueId });
};

const setCameraBrightnessInfo = (deviceId, brightness, wholeImageBrightness) => {
  getStorageSingletonInstance().setItem(`CameraBrightnessInfo_${deviceId}`, { brightness, wholeImageBrightness });
};

/**
 * @param {string} deviceId
 * @returns {{type: string, name: string, uniqueId: (string | null)} | null}
 */
const getSessionVirtualBackgroundInfo = (deviceId) => getStorageSingletonInstance().getItem(`VirtualBackgroundInfo_${deviceId}`);

const getCameraBrightnessInfo = (deviceId) => getStorageSingletonInstance().getItem(`CameraBrightnessInfo_${deviceId}`);

/**
 * @param {string} deviceId
 * @returns {{brightness: number, wholeImageBrightness: boolean}}
 */
const getCameraBrightnessInfoWithDefault = (deviceId) => getStorageSingletonInstance()
  .getItem(`CameraBrightnessInfo_${deviceId}`) || {
  brightness: 100,
  wholeImageBrightness: false,
};

const getSessionVirtualBackgroundInfoWithDefault = (deviceId) => getStorageSingletonInstance()
  .getItem(`VirtualBackgroundInfo_${deviceId}`) || {
  type: EFFECT_TYPES.NONE_TYPE,
  name: EFFECT_TYPES.NONE_TYPE,
};

const removeSessionVirtualBackgroundInfo = (deviceId) => getStorageSingletonInstance()
  .removeItem(`VirtualBackgroundInfo_${deviceId}`);

const ENGINES = ['tflite', 'longpipe'];
const ENGINE_STORAGE_KEY = 'bbb_virtual_background_engine';

// Which implementation runs the effect: 'tflite' (TFLite in WebAssembly with
// canvas compositing) or 'longpipe' (WebGPU/WebGL2). The default comes from
// public.virtualBackgrounds.engine. For testing, a browser can override it by
// opening the client with vbgEngine=longpipe or vbgEngine=tflite in the URL;
// the choice is remembered in that browser until vbgEngine=default is given.
// The URL is read here, when the client loads: the query string is removed
// from the address bar soon after.
try {
  const fromUrl = new URLSearchParams(window.location.search).get('vbgEngine');
  if (ENGINES.includes(fromUrl)) {
    window.localStorage.setItem(ENGINE_STORAGE_KEY, fromUrl);
  } else if (fromUrl === 'default') {
    window.localStorage.removeItem(ENGINE_STORAGE_KEY);
  }
} catch (error) {
  // No access to the URL or the storage: the configured engine is used.
}

const getVirtualBackgroundEngine = () => {
  try {
    const stored = window.localStorage.getItem(ENGINE_STORAGE_KEY);
    if (ENGINES.includes(stored)) return stored;
  } catch (error) {
    // No access to the storage: use the configured engine.
  }

  const { engine } = window.meetingClientSettings.public.virtualBackgrounds;

  return ENGINES.includes(engine) ? engine : 'tflite';
};

// Remembers the engine chosen in this browser (see the webcam settings).
const setVirtualBackgroundEngine = (engine) => {
  if (!ENGINES.includes(engine)) return;
  try {
    window.localStorage.setItem(ENGINE_STORAGE_KEY, engine);
  } catch (error) {
    // No access to the storage: the configured engine stays in use.
  }
};

// The TFLite engine does not run on Safari or on iOS.
const isVirtualBackgroundEngineSupported = (engine) => engine === 'longpipe'
  || !(deviceInfo.isIos || browserInfo.isSafari);

const isVirtualBackgroundSupported = () => isVirtualBackgroundEngineSupported(
  getVirtualBackgroundEngine(),
);

const getVirtualBgImagePath = () => {
  const {
    imagesPath: IMAGES_PATH = '/resources/images/virtual-backgrounds/',
  } = window.meetingClientSettings.public.virtualBackgrounds;

  return (getIsStoredOnBBB() ? getBasePath() : '') + IMAGES_PATH;
};

export {
  getBasePath,
  getImageNames,
  MODELS,
  BLUR_FILENAME,
  EFFECT_TYPES,
  setSessionVirtualBackgroundInfo,
  setCameraBrightnessInfo,
  getSessionVirtualBackgroundInfo,
  getSessionVirtualBackgroundInfoWithDefault,
  getCameraBrightnessInfo,
  getCameraBrightnessInfoWithDefault,
  removeSessionVirtualBackgroundInfo,
  isVirtualBackgroundSupported,
  getVirtualBackgroundEngine,
  setVirtualBackgroundEngine,
  isVirtualBackgroundEngineSupported,
  createVirtualBackgroundStream,
  getVirtualBackgroundThumbnail,
  getVirtualBgImagePath,
};
