package nlfilterlab;

record SimulationRequest(
        String fixture,
        String url,
        String contentType,
        int statusCode,
        FilterRule.CacheState cacheState,
        boolean cacheApiFailure) {
}
