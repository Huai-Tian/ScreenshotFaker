// Copyright (c) 2021-2025 ByteDance Inc.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//

// Created by Kelun Cai (caikelun@bytedance.com) on 2025-11-13.

#include "sh_ref.h"

#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>

#include "sh_util.h"

void sh_ref_init(sh_ref_t *self) {
  pthread_mutex_init(&self->lock, NULL);
  self->count = 0;
  self->destroy_ts = 0;
}

void sh_ref_uninit(sh_ref_t *self) {
  pthread_mutex_destroy(&self->lock);
}

void sh_ref_lock(sh_ref_t *self) {
  pthread_mutex_lock(&self->lock);
}

void sh_ref_unlock(sh_ref_t *self) {
  pthread_mutex_unlock(&self->lock);
}

void sh_ref_increment_count(sh_ref_t *self) {
  int old_count = __atomic_fetch_add(&self->count, 1, __ATOMIC_RELAXED);
  if (__predict_false(INT_MAX == old_count)) abort();
}

void sh_ref_decrement_count(sh_ref_t *self) {
  int old_count = __atomic_fetch_sub(&self->count, 1, __ATOMIC_RELEASE);
  if (__predict_false(old_count <= 0)) abort();
  if (old_count == 1) __atomic_thread_fence(__ATOMIC_ACQUIRE);
}

static int sh_ref_get_count(sh_ref_t *self) {
  return __atomic_load_n(&self->count, __ATOMIC_ACQUIRE);
}

bool sh_ref_is_destroyed(sh_ref_t *self) {
  return 0 != self->destroy_ts;
}

void sh_ref_delayed_destroy(sh_ref_t *self, sh_ref_mgr_t *mgr) {
  sh_ref_queue_t abandoned = TAILQ_HEAD_INITIALIZER(abandoned);
  sh_ref_t *ref, *tmp;
  uint32_t now;

  if (mgr->delay_sec > 0) {
    now = (uint32_t)sh_util_get_stable_timestamp();
    self->destroy_ts = now;
  } else {
    now = 0;
    self->destroy_ts = 1;
  }

  // Alternatively, a separate thread can be used to perform the destruction work,
  // which makes the destruction more timely, but the downside is that it requires
  // adding an extra thread. Either approach is probably acceptable.

  pthread_mutex_lock(&mgr->lock);
  TAILQ_INSERT_TAIL(&mgr->queue, self, link);
  if (mgr->delay_sec > 0) {
    TAILQ_FOREACH_SAFE(ref, &mgr->queue, link, tmp) {
      if (now > ref->destroy_ts && now - ref->destroy_ts > mgr->delay_sec) {
        if (0 == sh_ref_get_count(ref)) {
          TAILQ_REMOVE(&mgr->queue, ref, link);
          TAILQ_INSERT_TAIL(&abandoned, ref, link);
        }
      } else {
        break;
      }
    }
  } else {
    TAILQ_FOREACH_SAFE(ref, &mgr->queue, link, tmp) {
      if (0 == sh_ref_get_count(ref)) {
        TAILQ_REMOVE(&mgr->queue, ref, link);
        TAILQ_INSERT_TAIL(&abandoned, ref, link);
      }
    }
  }
  pthread_mutex_unlock(&mgr->lock);

  TAILQ_FOREACH_SAFE(ref, &abandoned, link, tmp) {
    TAILQ_REMOVE(&abandoned, ref, link);
    mgr->destroy(ref);
  }
}
