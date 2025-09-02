# Spark

To integrate Spark with DataHub, we provide a lightweight Java agent that listens for Spark application and job events
and pushes metadata out to DataHub in real-time. The agent listens to events such application start/end, and
SQLExecution start/end to create pipelines (i.e. DataJob) and tasks (i.e. DataFlow) in Datahub along with lineage to
datasets that are being read from and written to. Read on to learn how to configure this for different Spark scenarios.

## Configuring Spark agent

The Spark agent can be configured using a config file or while creating a Spark Session. If you are using Spark on
Databricks, refer [Configuration Instructions for Databricks](#configuration-instructions--databricks).

### Before you begin: Versions and Release Notes

Versioning of the jar artifact will follow the semantic versioning of the
main [DataHub repo](https://github.com/datahub-project/datahub) and release notes will be
available [here](https://github.com/datahub-project/datahub/releases).
Always check [the Maven central repository](https://search.maven.org/search?q=a:acryl-spark-lineage) for the latest
released version.

### Configuration Instructions: spark-submit

When running jobs using spark-submit, the agent needs to be configured in the config file.

```text
#Configuring DataHub spark agent jar
spark.jars.packages                          io.acryl:acryl-spark-lineage:0.2.3
spark.extraListeners                         datahub.spark.DatahubSparkListener
spark.datahub.rest.server                    http://localhost:8080
```

## spark-submit command line

```sh
spark-submit --packages io.acryl:acryl-spark-lineage:0.2.3 --conf "spark.extraListeners=datahub.spark.DatahubSparkListener" my_spark_job_to_run.py
```

### Configuration Instructions:  Amazon EMR

Set the following spark-defaults configuration properties as it
stated [here](https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark-configure.html)

```text
spark.jars.packages                          io.acryl:acryl-spark-lineage:0.2.3
spark.extraListeners                         datahub.spark.DatahubSparkListener
spark.datahub.rest.server                    https://your_datahub_host/gms
#If you have authentication set up then you also need to specify the Datahub access token
spark.datahub.rest.token                     yourtoken
```

### Configuration Instructions: Notebooks

When running interactive jobs from a notebook, the listener can be configured while building the Spark Session.

```python
spark = SparkSession.builder
.master("spark://spark-master:7077")
.appName("test-application")
.config("spark.jars.packages", "io.acryl:acryl-spark-lineage:0.2.3")
.config("spark.extraListeners", "datahub.spark.DatahubSparkListener")
.config("spark.datahub.rest.server", "http://localhost:8080")
.enableHiveSupport()
.getOrCreate()
```

### Configuration Instructions: Standalone Java Applications

The configuration for standalone Java apps is very similar.

```java
spark =SparkSession.

builder()
        .

appName("test-application")
        .

config("spark.master","spark://spark-master:7077")
        .

config("spark.jars.packages","io.acryl:acryl-spark-lineage:0.2.3")
        .

config("spark.extraListeners","datahub.spark.DatahubSparkListener")
        .

config("spark.datahub.rest.server","http://localhost:8080")
        .

enableHiveSupport()
        .

getOrCreate();
 ```

### Configuration Instructions:  Databricks

The Spark agent can be configured using Databricks
Cluster [Spark configuration](https://docs.databricks.com/clusters/configure.html#spark-configuration)
and [Init script](https://docs.databricks.com/clusters/configure.html#init-scripts).

[Databricks Secrets](https://docs.databricks.com/security/secrets/secrets.html) can be leveraged to store sensitive
information like tokens.

- Download `datahub-spark-lineage` jar
  from [the Maven central repository](https://s01.oss.sonatype.org/content/groups/public/io/acryl/acryl-spark-lineage/).
- Create `init.sh` with below content

    ```sh
    #!/bin/bash
    cp /dbfs/datahub/datahub-spark-lineage*.jar /databricks/jars
    ```

- Install and configure [Databricks CLI](https://docs.databricks.com/dev-tools/cli/index.html).
- Copy jar and init script to Databricks File System(DBFS) using Databricks CLI.

    ```sh
    databricks fs mkdirs dbfs:/datahub
    databricks fs cp --overwrite datahub-spark-lineage*.jar dbfs:/datahub
    databricks fs cp --overwrite init.sh dbfs:/datahub
    ```

- Open Databricks Cluster configuration page. Click the **Advanced Options** toggle. Click the **Spark** tab. Add below
  configurations under `Spark Config`.

    ```text
    spark.extraListeners                datahub.spark.DatahubSparkListener
    spark.datahub.rest.server           http://localhost:8080
    spark.datahub.databricks.cluster    cluster-name<any preferred cluster identifier>
    ```

- Click the **Init Scripts** tab. Set cluster init script as `dbfs:/datahub/init.sh`.

- Configuring DataHub authentication token

    - Add below config in cluster spark config.

      ```text
      spark.datahub.rest.token <token>
      ```

    - Alternatively, Databricks secrets can be used to secure token.
        - Create secret using Databricks CLI.

          ```sh
          databricks secrets create-scope --scope datahub --initial-manage-principal users
          databricks secrets put --scope datahub --key rest-token
          databricks secrets list --scope datahub &lt;&lt;Edit prompted file with token value&gt;&gt;
          ```

        - Add in spark config

          ```text
          spark.datahub.rest.token {{secrets/datahub/rest-token}}
          ```

## Configuration Options

| Field                                                               | Required | Default | Description                                                                                                                                                                               |
|---------------------------------------------------------------------|----------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| spark.jars.packages                                                 | ✅        |         | Set with latest/required version  io.acryl:datahub-spark-lineage:0.8.23                                                                                                                   |
| spark.extraListeners                                                | ✅        |         | datahub.spark.DatahubSparkListener                                                                                                                                                        |
| spark.datahub.rest.server                                           | ✅        |         | Datahub server url  eg:<http://localhost:8080>                                                                                                                                            |
| spark.datahub.rest.token                                            |          |         | Authentication token.                                                                                                                                                                     |
| spark.datahub.rest.disable_ssl_verification                         |          | false   | Disable SSL certificate validation. Caution: Only use this if you know what you are doing!                                                                                                |
| spark.datahub.rest.rest.max_retries                                 |          | 0       | Number of times a request retried if failed                                                                                                                                               |
| spark.datahub.rest.rest.retry_interval                              |          | 10      | Number of seconds to wait between retries                                                                                                                                                 |
| spark.datahub.metadata.pipeline.platformInstance                    |          |         | Pipeline level platform instance                                                                                                                                                          |
| spark.datahub.metadata.dataset.platformInstance                     |          |         | dataset level platform instance                                                                                                                                                           |
| spark.datahub.metadata.dataset.env                                  |          | PROD    | [Supported values](https://datahubproject.io/docs/graphql/enums#fabrictype). In all other cases, will fallback to PROD                                                                    |
| spark.datahub.metadata.table.hive_platform_alias                    |          | hive    | By default, datahub assigns Hive-like tables to the Hive platform. If you are using Glue as your Hive metastore, set this config flag to `glue`                                           |
| spark.datahub.metadata.include_scheme                               |          | true    | Include scheme from the path URI (e.g. hdfs://, s3://) in the dataset URN. We recommend setting this value to false, it is set to true for backwards compatibility with previous versions |
| spark.datahub.metadata.remove_partition_pattern                     |          |         | Remove partition pattern. (e.g. /partition=\d+) It change database/table/partition=123 to database/table                                                                                  |
| spark.datahub.coalesce_jobs                                         |          | true    | Only one datajob(task) will be emitted containing all input and output datasets for the spark application                                                                                 |
| spark.datahub.parent.datajob_urn                                    |          |         | Specified dataset will be set as upstream dataset for datajob created. Effective only when spark.datahub.coalesce_jobs is set to true                                                     |
| spark.datahub.metadata.dataset.materialize                          |          | false   | Materialize Datasets in DataHub                                                                                                                                                           |
| spark.datahub.platform.s3.path_spec_list                            |          |         | List of pathspec per platform                                                                                                                                                             |
| spark.datahub.metadata.dataset.experimental_include_schema_metadata | false    |         | Emit dataset schema metadata based on the spark                                                                                                                                           |
| spark.datahub.flow_name                                             |          |         | If it is set it will be used as the DataFlow name otherwise it uses spark app name as flow_name                                                                                           |
| spark.datahub.partition_regexp_pattern                              |          |         | Strip partition part from the path if path end matches with the specified regexp. Example `year=.*/month=.*/day=.*`                                                                       |
| spark.datahub.tags                                                  |          |         | Comma separated list of tags to attach to the DataFlow                                                                                                                                    |
| spark.datahub.domains                                               |          |         | Comma separated list of domain urns to attach to the DataFlow                                                                                                                             |
| spark.datahub.stage_metadata_coalescing                             |          |         | Normally it coalesce and send metadata at the onApplicationEnd event which is never called on Databricsk. You should enable this on Databricks if you want coalesced run .                |
| spark.datahub.patch.enabled                                         |          | false   | Set this to true to send lineage as a patch, which appends rather than overwrites existing Dataset lineage edges. By default it is enabled.                                               
|

## What to Expect: The Metadata Model

As of current writing, the Spark agent produces metadata related to the Spark job, tasks and lineage edges to datasets.

- A pipeline is created per Spark <master, appName>.
- A task is created per unique Spark query execution within an app.

For Spark on Databricks,

- A pipeline is created per
    - cluster_identifier: specified with spark.datahub.databricks.cluster
    - applicationID: on every restart of the cluster new spark applicationID will be created.
- A task is created per unique Spark query execution.

### Custom properties & relating to Spark UI

The following custom properties in pipelines and tasks relate to the Spark UI:

- appName and appId in a pipeline can be used to determine the Spark application
- description and SQLQueryId in a task can be used to determine the Query Execution within the application on the SQL
  tab of Spark UI
- Other custom properties of pipelines and tasks capture the start and end times of execution etc.

For Spark on Databricks, pipeline start time is the cluster start time.

### Spark versions supported

Supports Spark 3.x series.

### Environments tested with

This initial release has been tested with the following environments:

- spark-submit of Python/Java applications to local and remote servers
- Standalone Java applications
- Databricks Standalone Cluster

Testing with Databricks Standard and High-concurrency Cluster is not done yet.

### Configuring Hdfs based dataset URNs

Spark emits lineage between datasets. It has its own logic for generating urns. Python sources emit metadata of
datasets. To link these 2 things, urns generated by both have to match.
This section will help you to match urns to that of other ingestion sources.
By default, URNs are created using
template `urn:li:dataset:(urn:li:dataPlatform:<$platform>,<platformInstance>.<name>,<env>)`. We can configure these 4
things to generate the desired urn.

**Platform**:
Hdfs-based platforms supported explicitly:

- AWS S3 (s3)
- Google Cloud Storage (gcs)
- local ( local file system) (local)
  All other platforms will have "hdfs" as a platform.

**Name**:
By default, the name is the complete path. For Hdfs base datasets, tables can be at different levels in the path than
that of the actual file read due to various reasons like partitioning, and sharding. 'path_spec' is used to alter the
name.
{table} marker is used to specify the table level. Below are a few examples. One can specify multiple path_specs for
different paths specified in the `path_spec_list`. Each actual path is matched against all path_spes present in the
list. First, one to match will be used to generate urn.

**path_spec Examples**

```
spark.datahub.platform.s3.path_spec_list=s3://my-bucket/foo/{table}/year=*/month=*/day=*/*,s3://my-other-bucket/foo/{table}/year=*/month=*/day=*/*"
```

| Absolute path                        | path_spec                        | Urn                                                                          |
|--------------------------------------|----------------------------------|------------------------------------------------------------------------------|
| s3://my-bucket/foo/tests/bar.avro    | Not provided                     | urn:li:dataset:(urn:li:dataPlatform:s3,my-bucket/foo/tests/bar.avro,PROD)    |
| s3://my-bucket/foo/tests/bar.avro    | s3://my-bucket/foo/{table}/*     | urn:li:dataset:(urn:li:dataPlatform:s3,my-bucket/foo/tests,PROD)             |
| s3://my-bucket/foo/tests/bar.avro    | s3://my-bucket/foo/tests/{table} | urn:li:dataset:(urn:li:dataPlatform:s3,my-bucket/foo/tests/bar.avro,PROD)    |
| gs://my-bucket/foo/tests/bar.avro    | gs://my-bucket/{table}/*/*       | urn:li:dataset:(urn:li:dataPlatform:gcs,my-bucket/foo,PROD)                  |
| gs://my-bucket/foo/tests/bar.avro    | gs://my-bucket/{table}           | urn:li:dataset:(urn:li:dataPlatform:gcs,my-bucket/foo,PROD)                  |
| file:///my-bucket/foo/tests/bar.avro | file:///my-bucket/*/*/{table}    | urn:li:dataset:(urn:li:dataPlatform:local,my-bucket/foo/tests/bar.avro,PROD) |

**platform instance and env:**

The default value for env is 'PROD' and the platform instance is None. env and platform instances can be set for all
datasets using configurations 'spark.datahub.metadata.dataset.env' and 'spark.datahub.metadata.dataset.platformInstace'.
If spark is processing data that belongs to a different env or platform instance, then 'path_alias' can be used to
specify `path_spec` specific values of these. 'path_alias' groups the 'path_spec_list', its env, and platform instance
together.

path_alias_list Example:

The below example explains the configuration of the case, where files from 2 buckets are being processed in a single
spark application and files from my-bucket are supposed to have "instance1" as platform instance and "PROD" as env, and
files from bucket2 should have env "DEV" in their dataset URNs.

```
spark.datahub.platform.s3.path_alias_list :  path1,path2
spark.datahub.platform.s3.path1.env : PROD
spark.datahub.platform.s3.path1.path_spec_list: s3://my-bucket/*/*/{table}
spark.datahub.platform.s3.path1.platform_instance : instance-1
spark.datahub.platform.s3.path2.env: DEV
spark.datahub.platform.s3.path2.path_spec_list: s3://bucket2/*/{table}
```

### Important notes on usage

- It is advisable to ensure appName is used appropriately to ensure you can trace lineage from a pipeline back to your
  source code.
- If multiple apps with the same appName run concurrently, dataset-lineage will be captured correctly but the
  custom-properties e.g. app-id, SQLQueryId would be unreliable. We expect this to be quite rare.
- If spark execution fails, then an empty pipeline would still get created, but it may not have any tasks.
- For HDFS sources, the folder (name) is regarded as the dataset (name) to align with typical storage of parquet/csv
  formats.

### Debugging

- Following info logs are generated

On Spark context startup

```text
YY/MM/DD HH:mm:ss INFO DatahubSparkListener: DatahubSparkListener initialised.
YY/MM/DD HH:mm:ss INFO SparkContext: Registered listener datahub.spark.DatahubSparkListener
```

On application start

```text
YY/MM/DD HH:mm:ss INFO DatahubSparkListener: Application started: SparkListenerApplicationStart(AppName,Some(local-1644489736794),1644489735772,user,None,None)
YY/MM/DD HH:mm:ss INFO McpEmitter: REST Emitter Configuration: GMS url <rest.server>
YY/MM/DD HH:mm:ss INFO McpEmitter: REST Emitter Configuration: Token XXXXX
```

On pushing data to server

```text
YY/MM/DD HH:mm:ss INFO McpEmitter: MetadataWriteResponse(success=true, responseContent={"value":"<URN>"}, underlyingResponse=HTTP/1.1 200 OK [Date: day, DD month year HH:mm:ss GMT, Content-Type: application/json, X-RestLi-Protocol-Version: 2.0.0, Content-Length: 97, Server: Jetty(9.4.46.v20220331)] [Content-Length: 97,Chunked: false])
```

On application end

```text
YY/MM/DD HH:mm:ss INFO DatahubSparkListener: Application ended : AppName AppID
```

- To enable debugging logs, add below configuration in log4j.properties file

```properties
log4j.logger.datahub.spark=DEBUG
log4j.logger.datahub.client.rest=DEBUG
```

## How to build
Use Java 8 to build the project. The project uses Gradle as the build tool. To build the project, run the following command:

```shell
./gradlew -PjavaClassVersionDefault=8 :metadata-integration:java:spark-lineage-beta:shadowJar
```
## Known limitations

