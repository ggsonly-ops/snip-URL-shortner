package dev.snip.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

@Configuration
public class RedisConfig {

    /**
     * Token bucket. Read-compute-write has to be one indivisible operation or two
     * concurrent requests both see the last token and both proceed; Redis runs a
     * Lua script atomically, which is the entire reason this is Lua and not Java.
     */
    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }

    /** Sets k bits of the bloom filter in one round trip instead of k. */
    @Bean
    public RedisScript<Long> bloomAddScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/bloom_add.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /** Reads k bits and short-circuits on the first zero. */
    @Bean
    public RedisScript<Long> bloomCheckScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/bloom_check.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * BCrypt at cost 10. Password-protected links are a niche path, so the ~60ms
     * verify cost is acceptable; it never touches the ordinary redirect path.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
