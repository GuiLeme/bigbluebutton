/**
 * Longpipe-backed virtual background service.
 *
 * Exposes the same surface BBBVideoStream uses on VirtualBackgroundService
 * (startEffect, stopEffect, changeBackgroundImage, brightness,
 * wholeImageBrightness), so the two engines are interchangeable. Which one
 * runs is decided by getVirtualBackgroundEngine() in ./service.
 */
import logger from '/imports/startup/client/logger';
import { getVirtualBgImagePath } from '/imports/ui/services/virtual-background/service';

const toBackground = ({ backgroundType, isVirtualBackground, virtualSource }) => {
  if (isVirtualBackground || backgroundType === 'image') return { image: virtualSource };
  if (backgroundType === 'blur') return 'blur';
  return 'none';
};

class LongpipeVirtualBackgroundService {
  constructor(EffectsPipeline, parameters, settings = {}) {
    this._EffectsPipeline = EffectsPipeline;
    this._parameters = parameters;
    this._settings = settings;
    this._pipeline = null;
    this._brightness = 100;
    this._wholeImageBrightness = false;
  }

  /**
   * @param {MediaStream} stream - The camera stream.
   * @returns {MediaStream} - The stream with the effect applied. It carries the
   * unprocessed camera until the model is ready.
   */
  startEffect(stream) {
    const {
      weightsBaseUrl,
      preset,
      backend,
      debug,
    } = this._settings;
    const options = {
      background: toBackground(this._parameters),
      audio: 'drop',
      onReady: (info) => {
        logger.info({
          logCode: 'virtualbg_longpipe_ready',
          extraInfo: info,
        }, `Longpipe virtual background ready: ${info.backend}, preset ${info.preset}`);
      },
      onError: (error) => {
        logger.warn({
          logCode: 'virtualbg_longpipe_error',
          extraInfo: {
            errorType: error.type,
            errorMessage: error.message,
            fatal: error.fatal,
          },
        }, `Longpipe virtual background error: ${error.type}`);
      },
    };
    if (weightsBaseUrl) options.weightsBaseUrl = weightsBaseUrl;
    if (preset) options.preset = preset;
    if (backend) options.backend = backend;
    if (debug) options.debug = true;

    this._pipeline = new this._EffectsPipeline(new MediaStream(stream.getVideoTracks()), options);
    // For checking from the browser console which engine and preset are live:
    // await window.bbbVirtualBackground.pipeline.getStats()
    window.bbbVirtualBackground = { engine: 'longpipe', pipeline: this._pipeline };

    return this._pipeline.stream;
  }

  stopEffect() {
    if (this._pipeline) {
      this._pipeline.destroy();
      this._pipeline = null;
    }
    window.bbbVirtualBackground = { engine: 'longpipe', pipeline: null };
  }

  changeBackgroundImage(parameters = null) {
    const {
      name = '',
      type = 'blur',
      isVirtualBackground = false,
      customParams,
    } = parameters || {};

    this._parameters = {
      backgroundType: type,
      isVirtualBackground,
      virtualSource: customParams ? customParams.file : getVirtualBgImagePath() + name,
    };

    if (!this._pipeline) return;

    this._pipeline.setBackground(toBackground(this._parameters)).catch((error) => {
      logger.warn({
        logCode: 'virtualbg_longpipe_set_background_error',
        extraInfo: { errorMessage: error.message },
      }, `Longpipe virtual background change failed: ${error.message}`);
    });
  }

  // Longpipe has no brightness control. The values are kept so the camera
  // settings still read back what was set, but they change nothing.
  set brightness(value) {
    this._brightness = value;
  }

  get brightness() {
    return this._brightness;
  }

  set wholeImageBrightness(value) {
    this._wholeImageBrightness = value;
  }

  get wholeImageBrightness() {
    return this._wholeImageBrightness;
  }
}

/**
 * Takes the parameters createVirtualBackgroundService takes.
 * @param {Object} parameters
 * @returns {LongpipeVirtualBackgroundService}
 */
export async function createLongpipeVirtualBackgroundService(parameters = null) {
  // Loaded on demand: the SDK is only fetched when this engine is used.
  const { EffectsPipeline } = await import(/* webpackChunkName: "longpipe" */ 'longpipe');
  const virtualBackgroundImagePath = getVirtualBgImagePath();
  let normalized;

  if (parameters == null) {
    normalized = {
      backgroundType: 'blur',
      isVirtualBackground: false,
      virtualSource: virtualBackgroundImagePath,
    };
  } else {
    normalized = {
      backgroundType: parameters.backgroundType,
      isVirtualBackground: parameters.isVirtualBackground,
      virtualSource: parameters.customParams
        ? parameters.customParams.file
        : virtualBackgroundImagePath + parameters.backgroundFilename,
    };
  }

  const { longpipe = {} } = window.meetingClientSettings.public.virtualBackgrounds;

  return new LongpipeVirtualBackgroundService(EffectsPipeline, normalized, longpipe);
}

export default LongpipeVirtualBackgroundService;
