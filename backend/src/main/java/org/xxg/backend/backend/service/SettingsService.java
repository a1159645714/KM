package org.xxg.backend.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xxg.backend.backend.entity.Setting;
import org.xxg.backend.backend.mapper.SettingsMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统设置服务
 */
@Service
public class SettingsService {

    private static final Set<String> PUBLIC_SETTING_KEYS = Set.of(
            "aggregatedLogin",
            "oauth_login_types",
            "maintenance_notice",
            "site_url"
    );

    private final SettingsMapper settingsMapper;

    public SettingsService(SettingsMapper settingsMapper) {
        this.settingsMapper = settingsMapper;
    }

    /**
     * 获取所有设置并转为Map
     */
    public Map<String, String> getAllSettings() {
        List<Setting> settings = settingsMapper.findAll();
        Map<String, String> settingsMap = new HashMap<>();
        for (Setting setting : settings) {
            settingsMap.put(setting.getName(), setting.getValue());
        }
        return settingsMap;
    }

    public Map<String, String> getPublicSettings() {
        Map<String, String> allSettings = getAllSettings();
        Map<String, String> publicSettings = new HashMap<>();
        for (String key : PUBLIC_SETTING_KEYS) {
            if (allSettings.containsKey(key)) {
                publicSettings.put(key, allSettings.get(key));
            }
        }
        return publicSettings;
    }

    /**
     * 获取指定前缀的设置
     */
    public Map<String, String> getSettingsByPrefix(String prefix) {
        Map<String, String> all = getAllSettings();
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 获取单个设置值
     */
    public String getSetting(String name) {
        Setting setting = settingsMapper.findByName(name);
        return setting != null ? setting.getValue() : null;
    }

    /**
     * 批量保存设置
     */
    @Transactional
    public void saveSettings(Map<String, String> settings) {
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            if (entry.getValue() != null) {
                settingsMapper.save(entry.getKey(), entry.getValue());
            }
        }
    }
}
