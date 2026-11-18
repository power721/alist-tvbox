package cn.har01d.atmaintenance.tools;

/** Cold AT main-H2 recovery only. Does not start Spring, AT, AList or any worker. */
public final class EmbeddedDatabaseArchiveTool {
    private EmbeddedDatabaseArchiveTool() {}

    public static void main(String[] args) {
        DatabaseArchiveTool.run(args, System.getenv().getOrDefault("AT_DB_PASSWORD", ""),
                DatabaseArchiveTool.Deployment.AT_EMBEDDED);
    }
}
