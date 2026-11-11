<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';

type SpeechWidget = {
  setDisabled(disabled: boolean): void;
  commit(): void;
  cancel(): void;
  destroy(): void;
};

type SpeechOptions = {
  root: HTMLElement;
  input: HTMLTextAreaElement;
  getText(): string;
  setText(text: string): void;
  onBusyChange(busy: boolean): void;
  onNotice(message: string): void;
};

const props = defineProps<{
  modelValue: string;
  input: HTMLTextAreaElement | null;
  disabled: boolean;
}>();
const emit = defineEmits<{
  'update:modelValue': [text: string];
  busy: [busy: boolean];
  notice: [message: string];
}>();
const root = ref<HTMLElement | null>(null);
const failed = ref(false);
let widget: SpeechWidget | null = null;
let disposed = false;

onMounted(async () => {
  const assetRoot = '/api/v1/speech/assets/';
  if (!document.getElementById('speech-input-styles')) {
    const link = document.createElement('link');
    link.id = 'speech-input-styles';
    link.rel = 'stylesheet';
    link.href = `${assetRoot}speech-input.css`;
    document.head.append(link);
  }
  try {
    const url = `${assetRoot}speech-input.js`;
    const module = await import(/* @vite-ignore */ url) as {
      SpeechInput: new (options: SpeechOptions) => SpeechWidget;
    };
    await nextTick();
    if (disposed || !root.value || !props.input) {
      return;
    }
    widget = new module.SpeechInput({
      root: root.value,
      input: props.input,
      getText: () => props.modelValue,
      setText: text => emit('update:modelValue', text),
      onBusyChange: busy => emit('busy', busy),
      onNotice: message => emit('notice', message),
    });
    widget.setDisabled(props.disabled);
  } catch {
    if (!disposed) {
      failed.value = true;
    }
  }
});

watch(() => props.disabled, disabled => widget?.setDisabled(disabled));
onUnmounted(() => {
  disposed = true;
  widget?.destroy();
});

defineExpose({
  cancel: () => widget?.cancel(),
  commit: () => widget?.commit(),
});
</script>

<template>
  <div ref="root" class="voice-input">
    <button v-if="failed" type="button" class="voice-unavailable" @click="emit('notice', '语音模块未加载，请确认已启动修改后的后端并刷新页面')">语音未就绪</button>
  </div>
</template>

<style scoped>
.voice-input {
  display: contents;
}

.voice-unavailable {
  font-size: 12px;
}
</style>
