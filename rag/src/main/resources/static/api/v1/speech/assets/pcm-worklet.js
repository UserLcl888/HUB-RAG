/* 浏览器原始采样率 → 16kHz mono s16le；跨块保留 FIR、相位和尾帧状态。 */
class PcmRecorder extends AudioWorkletProcessor {
  constructor() {
    super();
    this.active = false;
    this.targetRate = 16000;
    this.ratio = sampleRate / this.targetRate;
    this.taps = 63;
    this.coefficients = new Float64Array(this.taps);
    const cutoff = Math.min(1, this.targetRate / sampleRate) * 0.45;
    let total = 0;
    for (let index = 0; index < this.taps; index += 1) {
      const offset = index - (this.taps - 1) / 2;
      const sinc = offset === 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * offset) / (Math.PI * offset);
      const window = 0.54 - 0.46 * Math.cos(2 * Math.PI * index / (this.taps - 1));
      this.coefficients[index] = sinc * window;
      total += this.coefficients[index];
    }
    for (let index = 0; index < this.taps; index += 1) {
      this.coefficients[index] /= total;
    }
    this.port.onmessage = ({ data }) => {
      if (data.type === 'start') {
        this.history = new Float64Array(this.taps);
        this.historyPosition = 0;
        this.inputIndex = 0;
        this.nextOutputAt = 0;
        this.previousFiltered = 0;
        this.sampleCount = 0;
        this.maxSamples = Number.isInteger(data.maxSamples)
          ? Math.max(1, Math.min(960000, data.maxSamples)) : 960000;
        this.buffer = new ArrayBuffer(3200);
        this.view = new DataView(this.buffer);
        this.frameSamples = 0;
        this.energy = 0;
        this.active = true;
      } else if (data.type === 'flush') {
        this.active = false;
        this.flushFrame();
        this.port.postMessage({ type: 'flushed' });
      }
    };
  }

  flushFrame() {
    if (!this.frameSamples) {
      return;
    }
    const pcm = this.buffer.slice(0, this.frameSamples * 2);
    const level = Math.min(1, Math.sqrt(this.energy / this.frameSamples) * 6);
    this.port.postMessage({ type: 'pcm', pcm, level }, [pcm]);
    this.frameSamples = 0;
    this.energy = 0;
  }

  sample(value) {
    this.history[this.historyPosition] = value;
    let filtered = 0;
    let cursor = this.historyPosition;
    for (let tap = 0; tap < this.taps; tap += 1) {
      filtered += this.history[cursor] * this.coefficients[tap];
      cursor = (cursor + this.taps - 1) % this.taps;
    }
    this.historyPosition = (this.historyPosition + 1) % this.taps;
    if (this.inputIndex >= this.nextOutputAt) {
      const fraction = Math.max(0, Math.min(1, 1 - (this.inputIndex - this.nextOutputAt)));
      const output = Math.max(-1, Math.min(1, this.previousFiltered + (filtered - this.previousFiltered) * fraction));
      this.view.setInt16(this.frameSamples * 2, Math.round(output * (output < 0 ? 32768 : 32767)), true);
      this.frameSamples += 1;
      this.sampleCount += 1;
      this.energy += output * output;
      this.nextOutputAt += this.ratio;
      if (this.frameSamples === 1600) {
        this.flushFrame();
      }
      if (this.sampleCount >= this.maxSamples) {
        this.active = false;
        this.flushFrame();
        this.port.postMessage({ type: 'limit' });
      }
    }
    this.previousFiltered = filtered;
    this.inputIndex += 1;
  }

  process(inputs, outputs) {
    // 保持处理节点运行，但绝不向扬声器播放麦克风声音。
    for (const output of outputs) {
      for (const channel of output) {
        channel.fill(0);
      }
    }
    const channels = inputs[0];
    if (this.active && channels?.length) {
      for (let index = 0; index < channels[0].length && this.active; index += 1) {
        let mono = 0;
        for (const channel of channels) {
          mono += channel[index];
        }
        this.sample(mono / channels.length);
      }
    }
    return true;
  }
}

registerProcessor('hbnt-pcm-recorder', PcmRecorder);
