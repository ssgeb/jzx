# Single Image Result Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the selected single image, detection action, annotated result, category, and confidence inside one responsive module.

**Architecture:** Modify the active `ImageDetection.vue` workspace only. Replace the external result card with a persistent result pane inside the single-image input block, and use scoped CSS to expose complete upload names and switch the pane below the controls on narrow screens.

**Tech Stack:** Vue 3 Composition API, Element Plus, scoped CSS, Node contract tests, Playwright

---

### Task 1: Lock the result-panel contract

**Files:**
- Modify: `frontend/tests/single-image-preview-contract.test.cjs`
- Test: `frontend/tests/single-image-preview-contract.test.cjs`

- [ ] **Step 1: Write the failing structural assertions**

Add assertions requiring an embedded panel and forbidding the old external card:

```js
assert.match(activeDetectionPage, /class="single-detection-workspace"/)
assert.match(activeDetectionPage, /class="single-detection-result"/)
assert.match(activeDetectionPage, /class="single-upload-file-name"/)
assert.match(activeDetectionPage, /v-loading="loadingSingle"/)
assert.doesNotMatch(activeDetectionPage, /class="detection-card result-card"/)
```

- [ ] **Step 2: Run the contract and verify RED**

Run: `cd frontend && node tests/single-image-preview-contract.test.cjs`

Expected: FAIL because `single-detection-workspace` does not exist.

- [ ] **Step 3: Keep existing upload lifecycle assertions intact**

Do not remove the existing checks for `auto-upload`, `on-change`, `on-remove`, object URL creation, and URL cleanup. These protect the image preview fix while the template moves.

### Task 2: Embed the result pane and expose the full filename

**Files:**
- Modify: `frontend/src/views/ImageDetection.vue:48-80`
- Modify: `frontend/src/views/ImageDetection.vue:369-392`
- Modify: `frontend/src/views/ImageDetection.vue:2146-2185`
- Modify: `frontend/src/views/ImageDetection.vue:2700-2745`
- Test: `frontend/tests/single-image-preview-contract.test.cjs`

- [ ] **Step 1: Replace the single-image block with a two-pane workspace**

Keep the existing upload component and button in the left pane. Add a filename element whose `title` contains the complete filename:

```vue
<div class="single-detection-workspace">
  <div class="single-detection-controls">
    <el-upload
      v-model:file-list="fileList"
      :auto-upload="false"
      :on-change="handleUploadChange"
      :on-remove="handleRemove"
      accept=".jpg,.jpeg,.png"
      list-type="picture"
      :limit="1"
    >
      <el-button type="primary" class="wide-button">选择单张图片</el-button>
    </el-upload>
    <div
      v-if="form.imageFile"
      class="single-upload-file-name"
      :title="form.imageFile.name"
    >
      {{ form.imageFile.name }}
    </div>
    <el-button
      type="default"
      class="wide-button secondary-action"
      :disabled="!form.imageFile"
      :loading="loadingSingle"
      @click="handleSingleDetection"
    >
      开始单图检测
    </el-button>
  </div>
  <section class="single-detection-result" v-loading="loadingSingle">
    <!-- result states -->
  </section>
</div>
```

- [ ] **Step 2: Render all result states inside the right pane**

Use the annotated image when a result exists, the selected local preview while waiting, and an empty state before selection:

```vue
<template v-if="detectionResult">
  <img :src="annotatedImageUrl" alt="单图检测标注结果" />
  <el-descriptions bordered :column="1">
    <el-descriptions-item label="检测类别">
      <el-tag :type="getCategoryType(detectionResult.category)">
        {{ getCategoryText(detectionResult.category) }}
      </el-tag>
    </el-descriptions-item>
    <el-descriptions-item label="置信度">
      {{ formatConfidence(detectionResult.confidence) }}
    </el-descriptions-item>
  </el-descriptions>
</template>
<template v-else-if="fileList[0]?.url">
  <img :src="fileList[0].url" alt="待检测图片预览" />
  <p>图片已就绪，点击开始单图检测</p>
</template>
<el-empty v-else description="请先选择图片并开始检测" />
```

- [ ] **Step 3: Remove obsolete external result behavior**

Delete the external `detection-card result-card`, the `singleResultCard` ref, `nextTick` import, and `scrollIntoView` call. Keep the success message, changing it to `单图检测完成` because the result is already in view.

- [ ] **Step 4: Add responsive and filename CSS**

```css
.single-detection-workspace {
  display: grid;
  grid-template-columns: minmax(220px, 0.85fr) minmax(300px, 1.15fr);
  gap: 18px;
}

.single-upload-file-name,
:deep(.el-upload-list__item-name) {
  white-space: normal;
  overflow-wrap: anywhere;
  text-overflow: clip;
}

@media (max-width: 900px) {
  .single-detection-workspace {
    grid-template-columns: 1fr;
  }
}
```

- [ ] **Step 5: Run the contract and verify GREEN**

Run: `cd frontend && node tests/single-image-preview-contract.test.cjs`

Expected: `single image upload preview contract assertions passed`.

### Task 3: Verify real browser behavior and build output

**Files:**
- Test: `frontend/tests/single-image-preview-contract.test.cjs`

- [ ] **Step 1: Run all frontend contracts**

Run every `frontend/tests/*.test.cjs` file with Node.

Expected: all 21 contract files exit with code 0.

- [ ] **Step 2: Run the production build**

Run: `cd frontend && npm run build`

Expected: Vite exits with code 0.

- [ ] **Step 3: Verify desktop behavior with Playwright**

At `1366x768`, log in, open `#/inspection/workbench`, select a long-named image, and detect it. Assert the complete filename text is visible, `/api/image-detection/upload` returns 200, `.single-detection-result img` has a non-zero natural width, and the result pane is in the viewport without scrolling to the page bottom.

- [ ] **Step 4: Verify narrow-screen behavior with Playwright**

At `720x900`, repeat the flow. Assert the result pane is below the controls, both remain within the content width, and no horizontal overflow is introduced.

- [ ] **Step 5: Check the patch**

Run: `git diff --check`

Expected: exit code 0 with no whitespace errors.
