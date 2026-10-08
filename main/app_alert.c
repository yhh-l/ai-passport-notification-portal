#include "app_alert.h"
#include "bsp_audio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <stdint.h>

static TaskHandle_t s_task;
static volatile int s_pending;
static volatile int s_enabled = 1;
static void sound_task(void *arg)
{
    (void)arg;
    // PCM is generated on a worker task; never block the button/LVGL task.
    int16_t pcm[256];
    for (;;) {
        if (!s_pending || !s_enabled) { vTaskDelay(pdMS_TO_TICKS(15)); continue; }
        s_pending = 0;
        if (bsp_audio_set_format(16000, 16, 1) != ESP_OK) continue;
        bsp_audio_set_volume(55);
        // A short, gentle two-note alert; not a percussive/woodblock effect.
        for (int block = 0; block < 12; block++) {
            for (int i = 0; i < 256; i++) {
                int sample = block * 256 + i;
                int half_period = sample < 1536 ? 18 : 14;
                int tone = ((sample / half_period) & 1) ? 1 : -1;
                int fade = (3072 - sample) * 2000 / 3072;
                pcm[i] = (int16_t)(tone * fade);
            }
            bsp_audio_write(pcm, sizeof(pcm));
            if (s_pending) break;
        }
    }
}
void app_alert_start(void) { xTaskCreate(sound_task, "notify_audio", 3072, NULL, 4, &s_task); }
void app_alert_play(void) { s_pending = 1; }
void app_alert_set_enabled(int enabled) { s_enabled = enabled; if (!enabled) s_pending = 0; }
