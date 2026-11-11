const ASSET_ROOT = '/api/v1/speech/assets/';
const MAX_DURATION_MS = 60000;
const MAX_SAMPLES = MAX_DURATION_MS * 16;
const ICONS = {
  mic: ['M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z', 'M5 10v2a7 7 0 0 0 14 0v-2', 'M12 19v3', 'M8 22h8'],
  cancel: ['M6 6l12 12', 'M18 6 6 18'],
  pause: ['M9 5v14', 'M15 5v14'],
  resume: ['M8 5l11 7-11 7Z'],
  done: ['M5 12l4 4L19 6'],
};

function createElement(tag, className, text = '') {
  const element = document.createElement(tag);
  element.className = className;
  element.textContent = text;
  return element;
}

function createButton(className, label, icon) {
  const button = createElement('button', className);
  button.type = 'button';
  button.setAttribute('aria-label', label);
  button.title = label;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  for (const [key, value] of Object.entries({
    viewBox: '0 0 24 24', width: '20', height: '20', fill: 'none',
    stroke: 'currentColor', 'stroke-width': '1.8', 'stroke-linecap': 'round',
    'stroke-linejoin': 'round', 'aria-hidden': 'true',
  })) {
    svg.setAttribute(key, value);
  }
  button.append(svg);
  setButtonIcon(button, icon);
  return button;
}

function setButtonIcon(button, icon) {
  const svg = button.querySelector('svg');
  svg.replaceChildren();
  for (const shape of ICONS[icon]) {
    const path = document.createElementNS(svg.namespaceURI, 'path');
    path.setAttribute('d', shape);
    svg.appendChild(path);
  }
}

/**
 * Vue 主站和原生 iframe 共用的语音输入面板。
 * @param {object} options
 * @param {HTMLElement} options.root 按钮挂载点，面板定位到外层 composer。
 * @param {HTMLTextAreaElement} options.input 原输入框。
 * @param {() => string} options.getText 读取草稿。
 * @param {(text: string) => void} options.setText 回填草稿，不发送问题。
 * @param {(busy: boolean) => void} options.onBusyChange 禁止并发发送。
 * @param {(message: string) => void} options.onNotice 提示状态或错误。
 */
export class SpeechInput {
  constructor(options) {
    this.options = options;
    this.disabled = false;
    this.session = null;
    this.run = null;
    this.root = options.root;
    this.root.classList.add('speech-widget');
    this.mic = createButton('speech-mic', '语音输入', 'mic');
    this.panel = createElement('section', 'speech-panel');
    this.panel.hidden = true;
    this.panel.setAttribute('aria-label', '语音输入');
    this.controls = createElement('div', 'speech-controls');
    this.cancelButton = createButton('speech-cancel', '取消', 'cancel');
    const middle = createElement('div', 'speech-middle');
    this.status = createElement('span', 'speech-status');
    this.status.setAttribute('role', 'status');
    this.wave = createElement('div', 'speech-wave');
    this.wave.setAttribute('aria-hidden', 'true');
    this.bars = Array.from({ length: 17 }, () => createElement('i', 'speech-bar'));
    this.wave.append(...this.bars);
    this.timer = createElement('span', 'speech-timer', '00:00 / 01:00');
    this.toggle = createButton('speech-toggle', '暂停', 'pause');
    this.finishButton = createButton('speech-finish', '结束录音', 'done');
    const actions = createElement('div', 'speech-actions');
    actions.append(this.toggle, this.finishButton);
    middle.append(this.status, this.wave, this.timer);
    this.controls.append(this.cancelButton, middle, actions);
    this.panel.append(this.controls);
    this.root.append(this.panel, this.mic);
    this.mic.addEventListener('click', () => { void this.start(); });
    this.cancelButton.addEventListener('click', () => this.cancel());
    this.finishButton.addEventListener('click', () => this.finishRecording());
    this.toggle.addEventListener('click', () => {
      if (this.disabled) {
        return;
      }
      if (this.session?.phase === 'paused') {
        void this.start();
      } else {
        void this.stop();
      }
    });
    this.onPageHide = () => this.cancel();
    this.onKeydown = (event) => {
      if (event.key === 'Escape' && this.session) {
        event.preventDefault();
        this.cancel();
      }
    };
    window.addEventListener('pagehide', this.onPageHide);
    document.addEventListener('keydown', this.onKeydown);
  }

  /** 页面业务状态更新，不改变既有文字问答逻辑。 */
  setDisabled(disabled) {
    this.disabled = disabled;
    this.mic.disabled = disabled || Boolean(this.session);
    this.toggle.disabled = disabled || !['recording', 'paused'].includes(this.session?.phase);
    this.finishButton.disabled = this.toggle.disabled;
  }

  setPhase(session, phase) {
    if (this.session !== session) {
      return;
    }
    session.phase = phase;
    if (this.run) {
      this.run.phase = phase;
    }
    this.panel.dataset.phase = phase;
    this.status.textContent = {
      permission: '等待麦克风授权…',
      connecting: '连接中…',
      recording: '',
      finalizing: '识别收尾中…',
      paused: '已暂停，可编辑或发送',
    }[phase] || '';
    const label = phase === 'paused' ? '继续' : '暂停';
    this.toggle.title = label;
    this.toggle.setAttribute('aria-label', label);
    setButtonIcon(this.toggle, phase === 'paused' ? 'resume' : 'pause');
    this.wave.hidden = !['recording', 'paused'].includes(phase);
    this.timer.hidden = !['recording', 'paused'].includes(phase);
    this.setDisabled(this.disabled);
  }

  updateTimer() {
    const seconds = Math.min(60, Math.floor((this.session?.samples || 0) / 16000));
    this.timer.textContent = `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')} / 01:00`;
  }

  async start() {
    if (this.run || this.disabled || this.options.input.disabled) {
      return;
    }
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      this.options.onNotice('麦克风需要 localhost 或可信 HTTPS，请检查访问地址及 iframe 授权');
      return;
    }
    if (!window.AudioContext || !window.AudioWorkletNode) {
      this.options.onNotice('当前浏览器不支持录音处理，请使用新版 Chrome 或 Edge');
      return;
    }
    const input = this.options.input;
    const session = this.session || {
      draft: this.options.getText(),
      selectionStart: input.selectionStart,
      selectionEnd: input.selectionEnd,
      readOnly: input.readOnly,
      samples: 0,
    };
    if (session.samples >= MAX_SAMPLES) {
      return;
    }
    this.session = session;
    const run = {
      id: crypto.randomUUID(),
      session,
      draft: this.options.getText(),
      selectionStart: input.selectionStart,
      selectionEnd: input.selectionEnd,
      segments: new Map(),
      sentBytes: 0,
      maxSamples: MAX_SAMPLES - session.samples,
      stopReason: 'pause',
    };
    this.run = run;
    input.readOnly = true;
    this.options.onBusyChange(true);
    this.mic.disabled = true;
    this.panel.hidden = false;
    this.updateTimer();
    this.setPhase(session, 'permission');
    // 必须在用户点击的调用栈内创建并恢复上下文。
    try {
      run.context = new AudioContext();
      const resumed = run.context.resume();
      resumed.catch(() => {});
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
      if (this.run !== run) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }
      run.stream = stream;
      if (run.context.state === 'closed') {
        throw new Error('录音上下文已关闭，请重新点击麦克风');
      }
      this.setPhase(session, 'connecting');
      run.waitTimer = window.setTimeout(() => this.fail(run, '语音连接超时，请重试'), 10000);
      await resumed;
      await run.context.audioWorklet.addModule(`${ASSET_ROOT}pcm-worklet.js`);
      if (this.run !== run) {
        return;
      }
      run.node = new AudioWorkletNode(run.context, 'hbnt-pcm-recorder', {
        numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1],
      });
      run.node.connect(run.context.destination);
      run.source = run.context.createMediaStreamSource(stream);
      run.node.port.onmessage = ({ data }) => this.onAudio(run, data);
      run.node.onprocessorerror = () => this.fail(run, '录音处理异常，请重试');
      stream.getAudioTracks().forEach((track) => {
        track.onended = () => this.fail(run, '麦克风已断开，请重新选择设备');
      });
      run.context.onstatechange = () => {
        if (run.phase === 'recording' && run.context?.state !== 'running') {
          this.fail(run, '录音已被浏览器暂停，请重试');
        }
      };
      run.abort = new AbortController();
      const response = await fetch('/api/v1/speech/status', { signal: run.abort.signal });
      if (!response.ok) {
        throw new Error('语音后端未连接，请确认已启动修改后的后端');
      }
      const status = await response.json();
      if (!status.configured) {
        throw new Error(status.message || '后端尚未配置 ASR');
      }
      if (this.run !== run) {
        return;
      }
      const address = new URL('/api/v1/speech/recognize', location.href);
      address.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
      run.socket = new WebSocket(address);
      run.socket.onopen = () => {
        if (this.run === run) {
          run.socket.send(JSON.stringify({
            type: 'start', recordingId: run.id,
            format: 'pcm', sampleRate: 16000, channels: 1, bitsPerSample: 16,
          }));
        }
      };
      run.socket.onmessage = ({ data }) => this.onMessage(run, data);
      run.socket.onerror = () => this.fail(run, '语音连接失败，请检查后端和网络后重试');
      run.socket.onclose = ({ code }) => this.fail(run, code === 1013
        ? '语音服务繁忙，请稍后重试' : '语音连接中断，已保留确认文字');
    } catch (error) {
      const message = error?.name === 'NotAllowedError'
        ? '麦克风未获授权，请在浏览器和 iframe 设置中允许使用'
        : error?.name === 'NotFoundError' ? '未找到麦克风，请连接设备后重试'
          : error?.name === 'NotReadableError' ? '麦克风无法使用，请检查设备是否被占用'
            : error?.message || '无法开始录音，请重试';
      this.fail(run, message);
    }
  }

  onMessage(run, raw) {
    if (this.run !== run) {
      return;
    }
    try {
      const event = JSON.parse(raw);
      if (event.recordingId !== run.id && !(event.type === 'error' && !event.recordingId)) {
        return;
      }
      if (event.type === 'ready' && run.phase === 'connecting') {
        window.clearTimeout(run.waitTimer);
        this.setPhase(run.session, 'recording');
        run.node.port.postMessage({ type: 'start', maxSamples: run.maxSamples });
        run.source.connect(run.node);
      } else if (event.type === 'partial' || event.type === 'final') {
        if (!Number.isInteger(event.segmentId) || !Number.isInteger(event.revision) || typeof event.text !== 'string') {
          throw new Error('Invalid transcript');
        }
        const previous = run.segments.get(event.segmentId);
        if (previous?.final || (previous && previous.revision >= event.revision)) {
          return;
        }
        run.segments.set(event.segmentId, {
          text: event.text, revision: event.revision, final: event.type === 'final',
        });
        this.renderTranscript(run);
      } else if (event.type === 'complete') {
        this.finishRun(run, typeof event.text === 'string' ? event.text : this.confirmedText(run));
      } else if (event.type === 'error') {
        this.fail(run, event.message || '语音识别失败，请重试', event.confirmedText);
      }
    } catch {
      this.fail(run, '语音结果格式异常，请重试');
    }
  }

  onAudio(run, event) {
    if (this.run !== run) {
      return;
    }
    if (event.type === 'flushed') {
      run.resolveFlush?.();
    } else if (event.type === 'limit') {
      void this.stop('limit');
    } else if (event.type === 'pcm' && ['recording', 'finalizing'].includes(run.phase)) {
      if (run.socket?.readyState !== WebSocket.OPEN) {
        this.fail(run, '语音连接中断，已保留确认文字');
        return;
      }
      if (run.socket.bufferedAmount + event.pcm.byteLength > 64000) {
        this.fail(run, '网络较慢，音频发送积压，请重试');
        return;
      }
      if (run.sentBytes + event.pcm.byteLength > run.maxSamples * 2) {
        this.fail(run, '录音长度异常，已保留确认文字');
        return;
      }
      try {
        run.socket.send(event.pcm);
        run.sentBytes += event.pcm.byteLength;
        run.session.samples += event.pcm.byteLength / 2;
        this.updateTimer();
      } catch {
        this.fail(run, '音频发送失败，请重试');
        return;
      }
      this.bars.forEach((bar, index) => {
        const weight = 0.45 + 0.55 * Math.sin((index + 1) * Math.PI / 18);
        bar.style.height = `${3 + Math.round(30 * event.level * weight)}px`;
      });
      if (run.session.samples >= MAX_SAMPLES) {
        void this.stop('limit');
      }
    }
  }

  renderTranscript(run) {
    const text = [...run.segments.entries()].sort(([left], [right]) => left - right)
      .map(([, segment]) => segment.text).join('');
    this.fillTranscript(run, text);
  }

  /** 每次用当前录音片段替换预览，避免临时结果修订时重复追加。 */
  fillTranscript(run, text) {
    const hasText = Boolean(text.trim());
    const draft = hasText
      ? run.draft.slice(0, run.selectionStart) + text + run.draft.slice(run.selectionEnd)
      : run.draft;
    const cursor = hasText ? run.selectionStart + text.length : run.selectionStart;
    this.writeText(draft, cursor, hasText ? cursor : run.selectionEnd);
  }

  writeText(text, start, end = start) {
    const input = this.options.input;
    // 同步更新原生输入框，同时通知 Vue / emb 维护各自的草稿及高度。
    input.value = text;
    this.options.setText(text);
    input.setSelectionRange(start, end);
    input.scrollTop = input.scrollHeight;
  }

  confirmedText(run) {
    return [...run.segments.entries()].sort(([left], [right]) => left - right)
      .filter(([, segment]) => segment.final).map(([, segment]) => segment.text).join('');
  }

  async stop(reason = 'pause') {
    const run = this.run;
    if (run && ['finish', 'limit'].includes(reason)) {
      run.stopReason = reason;
    }
    if (!run || run.phase !== 'recording') {
      return;
    }
    this.setPhase(run.session, 'finalizing');
    try {
      await new Promise((resolve, reject) => {
        run.resolveFlush = () => {
          window.clearTimeout(run.flushTimer);
          resolve();
        };
        run.flushTimer = window.setTimeout(() => reject(new Error('尾部音频处理超时，已保留确认文字')), 800);
        run.node.port.postMessage({ type: 'flush' });
      });
      if (this.run !== run) {
        return;
      }
      this.releaseMicrophone(run);
      if (run.socket?.readyState !== WebSocket.OPEN) {
        throw new Error('语音连接中断，已保留确认文字');
      }
      run.socket.send(JSON.stringify({ type: 'stop', recordingId: run.id }));
      run.waitTimer = window.setTimeout(() => this.fail(run, '识别收尾超时，已保留确认文字'), 10000);
    } catch (error) {
      this.fail(run, error.message || '录音结束失败，请重试');
    }
  }

  releaseMicrophone(run) {
    if (run.stream) {
      run.stream.getTracks().forEach((track) => {
        track.onended = null;
        track.stop();
      });
    }
    if (run.context) {
      run.context.onstatechange = null;
      try {
        run.source?.disconnect();
        run.node?.disconnect();
      } catch {
        // 已经释放的音频节点不重复断开。
      }
      if (run.context.state !== 'closed') {
        void run.context.close().catch(() => {});
      }
    }
  }

  disposeRun(run) {
    this.run = null;
    window.clearTimeout(run.waitTimer);
    window.clearTimeout(run.flushTimer);
    run.resolveFlush?.();
    run.abort?.abort();
    this.releaseMicrophone(run);
    if (run.socket) {
      run.socket.onopen = null;
      run.socket.onmessage = null;
      run.socket.onerror = null;
      run.socket.onclose = null;
      if (run.socket.readyState === WebSocket.OPEN || run.socket.readyState === WebSocket.CONNECTING) {
        run.socket.close();
      }
    }
  }

  endSession() {
    const session = this.session;
    this.session = null;
    if (session) {
      this.options.input.readOnly = session.readOnly;
    }
    this.panel.hidden = true;
    this.setDisabled(this.disabled);
    this.options.onBusyChange(false);
  }

  finishRun(run, text, notice = '') {
    if (this.run !== run) {
      return;
    }
    this.disposeRun(run);
    this.fillTranscript(run, text);
    if (notice || run.stopReason !== 'pause') {
      this.endSession();
    } else {
      this.options.input.readOnly = run.session.readOnly;
      this.bars.forEach((bar) => { bar.style.height = '3px'; });
      this.setPhase(run.session, 'paused');
      this.options.onBusyChange(false);
    }
    if (notice || !text.trim()) {
      this.options.onNotice(notice || '未识别到文字，请重试');
    }
    this.options.input.focus();
  }

  fail(run, message, confirmedText) {
    if (this.run === run) {
      this.finishRun(run, typeof confirmedText === 'string' ? confirmedText : this.confirmedText(run), message);
    }
  }

  /** 对勾结束整次录音，等待尾句确认后保留文字，不发送问题。 */
  finishRecording() {
    if (this.disabled) {
      return;
    }
    if (this.session?.phase === 'paused') {
      this.commit();
      this.options.input.focus();
    } else if (this.session?.phase === 'recording') {
      void this.stop('finish');
    }
  }

  /** 暂停后手动发送或改问其他问题时，接纳现有文字并关闭面板。 */
  commit() {
    if (this.run) {
      return;
    }
    if (this.session) {
      this.endSession();
    }
  }

  /** 取消始终丢弃本次语音，包括已确认句子，恢复开始前的草稿。 */
  cancel() {
    const session = this.session;
    const run = this.run;
    if (!session) {
      return;
    }
    if (run?.socket?.readyState === WebSocket.OPEN) {
      try {
        run.socket.send(JSON.stringify({ type: 'cancel', recordingId: run.id }));
      } catch {
        // 取消期间即使连接断开，也必须继续释放本地设备。
      }
    }
    if (run) {
      this.disposeRun(run);
    }
    this.endSession();
    this.writeText(session.draft, session.selectionStart, session.selectionEnd);
  }

  destroy() {
    this.cancel();
    window.removeEventListener('pagehide', this.onPageHide);
    document.removeEventListener('keydown', this.onKeydown);
    this.root.replaceChildren();
  }
}
