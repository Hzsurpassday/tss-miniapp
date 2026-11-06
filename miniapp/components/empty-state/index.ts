/**
 * 空态 / 加载态 / 错误态通用占位组件。
 * 只在 `visible` 为 true 时展示，避免各页面重复实现。
 */

Component<
  Record<string, never>,
  {
    visible: { type: BooleanConstructor; value: boolean };
    text: { type: StringConstructor; value: string };
    hint: { type: StringConstructor; value: string };
    actionText: { type: StringConstructor; value: string };
  },
  { onAction(): void }
>({
  properties: {
    visible: { type: Boolean, value: false },
    text: { type: String, value: '暂无数据' },
    hint: { type: String, value: '' },
    actionText: { type: String, value: '' }
  },

  data: {},

  methods: {
    onAction: function (): void {
      this.triggerEvent('action', {});
    }
  }
});
