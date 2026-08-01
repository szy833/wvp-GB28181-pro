package com.genersoft.iot.vmp.utils.redis;

import com.google.common.collect.Lists;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Redis工具类
 *
 * @author swwheihei
 * @date 2020年5月6日 下午8:27:29
 */
@SuppressWarnings(value = {"rawtypes", "unchecked"})
public class RedisUtil {

    /**
     * 按 Redis Glob 模式扫描 Key。调用方负责提供完整的 MATCH pattern。
     *
     * @param redisTemplate Redis 模板
     * @param pattern Redis Glob 模式，支持 *, ?, [] 等通配符
     * @return 匹配到的 Key
     */
    public static List<Object> scan(RedisTemplate redisTemplate, String pattern) {

        Set<String> resultKeys = (Set<String>) redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            ScanOptions scanOptions = ScanOptions.scanOptions().match(pattern).count(1000).build();
            Cursor<byte[]> scan = connection.scan(scanOptions);
            Set<String> keys = new HashSet<>();
            while (scan.hasNext()) {
                byte[] next = scan.next();
                keys.add(new String(next));
            }
            return keys;
        });

        return Lists.newArrayList(resultKeys);
    }
}



