-- Set the k bits for one member of the bloom filter in a single round trip.
--
-- KEYS[1] = bit-array key
-- ARGV[1..k] = bit offsets, computed application-side (Java has the hash functions;
--              Lua would need a hand-rolled 64-bit implementation to match them)
--
-- returns the number of offsets written

for i = 1, #ARGV do
  redis.call('SETBIT', KEYS[1], tonumber(ARGV[i]), 1)
end

return #ARGV
