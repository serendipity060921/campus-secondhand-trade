<script setup>
/**
 * 评价弹窗（v0.18 / 第6批-㉑）
 *
 * 只让前端传 orderId —— 被评价人由后端按订单推导（前端无法把评价指向别人）。
 * 提交失败时把后端业务码翻成人话：9004=已评价过、9003=订单未完成、9002=非买卖双方。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { EVALUATION_TAGS, getOrderEvaluations, submitEvaluation } from '@/api/evaluation'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  order: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'submitted'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const submitting = ref(false)
const form = reactive({ rating: 5, content: '', tags: [], anonymous: 0 })

function reset() {
  form.rating = 5
  form.content = ''
  form.tags = []
  form.anonymous = 0
}

watch(visible, (v) => {
  if (v) reset()
})

async function submit() {
  if (!props.order?.id) return
  submitting.value = true
  try {
    const res = await submitEvaluation({
      orderId: props.order.id,
      rating: form.rating,
      content: form.content?.trim() || undefined,
      tags: form.tags.length ? form.tags.join(',') : undefined,
      anonymous: form.anonymous ? 1 : 0
    })
    ElMessage.success(res.message || '评价成功')
    visible.value = false
    emit('submitted', res.data)
  } catch (e) {
    const code = e?.code ?? e?.response?.data?.code
    const message = e?.message || '提交失败，请稍后重试'
    if (code === 9004) ElMessage.warning('你已经评价过这笔订单了')
    else if (code === 9003) ElMessage.warning('订单完成后才能评价')
    else if (code === 9002) ElMessage.warning('只有买卖双方可以评价该订单')
    else ElMessage.error(message)
  } finally {
    submitting.value = false
  }
}

/** 打开前先问后端"我评过没有"，避免用户填完才被拒 */
const checking = ref(false)
async function checkBeforeOpen() {
  if (!props.order?.id || checking.value) return false
  checking.value = true
  try {
    const res = await getOrderEvaluations(props.order.id)
    const mine = (res.data || []).some((x) => x.mine)
    if (mine) {
      ElMessage.info('你已经评价过这笔订单了')
      return false
    }
    return true
  } catch (e) {
    // 查询失败不阻塞提交：后端仍会做最终校验
    return true
  } finally {
    checking.value = false
  }
}

// 暴露给父组件（打开前预检）与组件测试（直接驱动表单与提交，避免依赖 stub 出来的 DOM)
defineExpose({ checkBeforeOpen, form, submit })
</script>

<template>
  <el-dialog v-model="visible" title="评价这笔交易" width="520px" :close-on-click-modal="!submitting">
    <div v-if="order" class="eval-order">
      <img v-if="order.productImage" class="eval-img" :src="order.productImage" :alt="order.productTitle" />
      <div>
        <div class="eval-title">{{ order.productTitle }}</div>
        <div class="eval-no">订单号：{{ order.orderNo }}</div>
      </div>
    </div>

    <el-form label-width="72px" :disabled="submitting">
      <el-form-item label="评分" required>
        <el-rate v-model="form.rating" />
        <span class="hint">4~5 星会为对方信用分 +1，1~2 星 −1</span>
      </el-form-item>
      <el-form-item label="标签">
        <el-checkbox-group v-model="form.tags">
          <el-checkbox v-for="t in EVALUATION_TAGS" :key="t" :value="t" :label="t" border size="small">
            {{ t }}
          </el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item label="内容">
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="说说这次交易的真实感受（可留空）"
        />
      </el-form-item>
      <el-form-item label="匿名">
        <el-switch v-model="form.anonymous" :active-value="1" :inactive-value="0" />
        <span class="hint">匿名只隐藏昵称与头像，管理端仍可追溯</span>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交评价</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.eval-order {
  display: flex;
  gap: var(--ct-space-3);
  align-items: center;
  padding: var(--ct-space-3);
  margin-bottom: var(--ct-space-4);
  background: var(--ct-bg-subtle);
  border-radius: var(--ct-radius-md);
}

.eval-img {
  width: 3rem;
  height: 3rem;
  object-fit: cover;
  border-radius: var(--ct-radius-sm);
}

.eval-title {
  font-size: var(--ct-text-base);
  color: var(--ct-ink-900);
}

.eval-no {
  margin-top: var(--ct-space-1);
  font-size: var(--ct-text-xs);
  color: var(--ct-text-muted);
}

.hint {
  margin-left: var(--ct-space-2);
  font-size: var(--ct-text-xs);
  color: var(--ct-text-muted);
}
</style>
