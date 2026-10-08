// AI Passport: a private Android notification display.
#include "app_ble.h"
#include "app_alert.h"
#include "bsp_audio.h"
#include "bsp_button.h"
#include "bsp_display.h"
#include "bsp_i2c.h"
#include "esp_log.h"
#include "lvgl.h"
#include "nvs_flash.h"
#include <stdio.h>
#include <string.h>

extern const lv_font_t font_chinese_16;
static const char *TAG = "passport_notify";
static lv_obj_t *s_message_label, *s_link_label, *s_alert_label, *s_category_label;
static uint32_t s_message_until;
static uint32_t s_last_code = UINT32_MAX;
static char s_message[161];
static bool s_muted, s_audio_ready;
static esp_err_t s_ble_error;

static lv_obj_t *label(lv_obj_t *parent, const char *text, int x, int y,
                       int w, int h, uint32_t color, lv_text_align_t align)
{
    lv_obj_t *obj = lv_label_create(parent);
    lv_label_set_text(obj, text);
    lv_label_set_long_mode(obj, LV_LABEL_LONG_WRAP);
    lv_obj_set_pos(obj, x, y);
    lv_obj_set_size(obj, w, h);
    lv_obj_set_style_text_font(obj, &font_chinese_16, 0);
    lv_obj_set_style_text_color(obj, lv_color_hex(color), 0);
    lv_obj_set_style_text_align(obj, align, 0);
    return obj;
}
static lv_obj_t *panel(lv_obj_t *parent, int x, int y, int w, int h,
                       int radius, uint32_t color)
{
    lv_obj_t *obj = lv_obj_create(parent);
    lv_obj_set_pos(obj, x, y);
    lv_obj_set_size(obj, w, h);
    lv_obj_set_style_bg_color(obj, lv_color_hex(color), 0);
    lv_obj_set_style_radius(obj, radius, 0);
    lv_obj_set_style_border_width(obj, 0, 0);
    lv_obj_set_style_pad_all(obj, 0, 0);
    lv_obj_remove_flag(obj, LV_OBJ_FLAG_SCROLLABLE);
    return obj;
}
static void hide_message(void)
{
    memset(s_message, 0, sizeof(s_message));
    s_message_until = 0;
    lv_label_set_text(s_category_label, "最新消息");
    lv_label_set_text(s_message_label, "等待手机通知...");
}
static void tick(lv_timer_t *timer)
{
    (void)timer;
    uint32_t now = lv_tick_get();
    app_message_t message;
    while (app_ble_receive(&message)) {
        // Sensitive message text lives in RAM only; never log or persist it.
        memset(s_message, 0, sizeof(s_message));
        snprintf(s_message, sizeof(s_message), "%s", message.text);
        s_message_until = now + (message.type == 1 ? 20000 : 12000);
        lv_label_set_text(s_category_label, message.type == 1 ? "短信通知" :
                          message.type == 2 ? "应用通知" : "连接提示");
        lv_label_set_text(s_message_label, s_message);
        if (s_audio_ready && !s_muted) app_alert_play();
    }
    if (s_message_until && (int32_t)(now - s_message_until) >= 0) hide_message();
    uint32_t code = app_ble_passkey();
    if (code != s_last_code) {
        s_last_code = code;
        if (code != UINT32_MAX) lv_label_set_text_fmt(s_link_label, "配对码 %06lu · 请在手机输入", (unsigned long)code);
    }
    if (code == UINT32_MAX) {
        lv_label_set_text(s_link_label, s_ble_error != ESP_OK ? "蓝牙启动失败" :
                          app_ble_authenticated() ? "手机已安全配对" :
                          app_ble_connected() ? "手机已连接 · 等待配对" : "等待连接 · PassportNotify");
    }
}
static void on_key(bsp_btn_t btn, bsp_btn_ev_t ev, void *user)
{
    (void)user;
    if (!bsp_lvgl_lock(200)) return;
    if (btn == BSP_BTN_UP && ev == BSP_BTN_CLICK) {
        hide_message();
    } else if (btn == BSP_BTN_DOWN && ev == BSP_BTN_CLICK) {
        s_muted = !s_muted;
        app_alert_set_enabled(!s_muted);
        lv_label_set_text(s_alert_label, s_muted ? "提示音已关闭" : "提示音已开启");
    } else if (btn == BSP_BTN_OK && ev == BSP_BTN_CLICK) {
        // Offline screen/button check, without displaying a cached notification.
        hide_message();
        lv_label_set_text(s_category_label, "设备自检");
        lv_label_set_text(s_message_label, "屏幕与按键正常");
        s_message_until = lv_tick_get() + 5000;
        if (s_audio_ready && !s_muted) app_alert_play();
    }
    bsp_lvgl_unlock();
}
static void build_ui(void)
{
    lv_obj_t *scr = lv_obj_create(NULL);
    lv_obj_set_style_bg_color(scr, lv_color_hex(0x101923), 0);
    lv_obj_set_style_border_width(scr, 0, 0);
    lv_obj_set_style_pad_all(scr, 0, 0);
    lv_obj_remove_flag(scr, LV_OBJ_FLAG_SCROLLABLE);
    label(scr, "随身消息", 16, 13, 208, 27, 0xF3F6FA, LV_TEXT_ALIGN_LEFT);
    s_link_label = label(scr, "等待连接 · PassportNotify", 16, 45, 208, 40, 0x94C5E8, LV_TEXT_ALIGN_LEFT);
    lv_obj_t *box = panel(scr, 12, 93, 216, 170, 13, 0x243443);
    s_category_label = label(box, "最新消息", 14, 14, 188, 27, 0x88D9C2, LV_TEXT_ALIGN_LEFT);
    s_message_label = label(box, "等待手机通知...", 14, 51, 188, 105, 0xFFFFFF, LV_TEXT_ALIGN_LEFT);
    s_alert_label = label(scr, "提示音已开启", 14, 271, 212, 22, 0x95AFC2, LV_TEXT_ALIGN_CENTER);
    label(scr, "上:隐藏  下:静音  OK:自检", 6, 296, 228, 22, 0x95AFC2, LV_TEXT_ALIGN_CENTER);
    lv_screen_load(scr);
    lv_timer_create(tick, 200, NULL);
}
void app_main(void)
{
    bsp_i2c_init();
    if (bsp_display_init() != ESP_OK || !bsp_lvgl_init()) {
        ESP_LOGE(TAG, "display init failed");
        return;
    }
    bsp_display_backlight(85);
    // BLE bonding needs NVS. Do not erase any existing device data on failure.
    esp_err_t nvs_error = nvs_flash_init();
    if (nvs_error != ESP_OK) ESP_LOGE(TAG, "NVS init failed: %s", esp_err_to_name(nvs_error));
    s_audio_ready = bsp_audio_init() == ESP_OK;
    if (s_audio_ready) app_alert_start();
    if (bsp_lvgl_lock(1000)) { build_ui(); bsp_lvgl_unlock(); }
    if (nvs_error == ESP_OK) s_ble_error = app_ble_start();
    else s_ble_error = nvs_error;
    if (s_ble_error != ESP_OK) ESP_LOGE(TAG, "BLE start failed: %s", esp_err_to_name(s_ble_error));
    esp_err_t err = bsp_button_init(on_key, NULL);
    if (err != ESP_OK) ESP_LOGE(TAG, "button init failed: %s", esp_err_to_name(err));
}
