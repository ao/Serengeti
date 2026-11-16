# Prints one /v1/query body inserting 2,000 small rows into d.small: n is a number, s a short string, both
# unique. Batch b (awk -v b=N) holds n = 2000*b ... 2000*b + 1999.
BEGIN {
  printf "{\"query\": \"INSERT INTO d.small (n, s) VALUES "
  for (i = 0; i < 2000; i++) {
    k = b * 2000 + i
    printf "%s(%d, 'k%d')", (i ? "," : ""), k, k
  }
  printf "\"}"
}
