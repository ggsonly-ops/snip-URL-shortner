-- Test membership: 1 = possibly present, 0 = definitely absent.
--
-- A bloom filter has no false negatives, so a 0 here is a hard "no such code" and
-- the caller can 404 immediately without touching the cache or the database. A 1 is
-- only "maybe", so the caller falls through to the normal lookup path.
--
-- KEYS[1] = bit-array key
-- ARGV[1..k] = bit offsets

for i = 1, #ARGV do
  if redis.call('GETBIT', KEYS[1], tonumber(ARGV[i])) == 0 then
    return 0
  end
end

return 1
