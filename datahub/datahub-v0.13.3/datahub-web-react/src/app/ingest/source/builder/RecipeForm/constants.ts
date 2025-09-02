import { SNOWFLAKE } from '../../conf/snowflake/snowflake';
import { BIGQUERY } from '../../conf/bigquery/bigquery';
import { REDSHIFT } from '../../conf/redshift/redshift';
import { LOOKER } from '../../conf/looker/looker';
import { TABLEAU } from '../../conf/tableau/tableau';
import { KAFKA } from '../../conf/kafka/kafka';
import {
    INCLUDE_LINEAGE,
    TABLE_PROFILING_ENABLED,
    STATEFUL_INGESTION_ENABLED,
    INCLUDE_TABLES,
    INCLUDE_VIEWS,
    DATABASE_ALLOW,
    DATABASE_DENY,
    TABLE_LINEAGE_MODE,
    INGEST_TAGS,
    INGEST_OWNER,
    EXTRACT_USAGE_HISTORY,
    EXTRACT_OWNERS,
    SKIP_PERSONAL_FOLDERS,
    RecipeField,
    START_TIME,
    INCLUDE_TABLE_LINEAGE,
    TABLE_DENY,
    VIEW_DENY,
    VIEW_ALLOW,
    TABLE_ALLOW,
    SCHEMA_DENY,
    SCHEMA_ALLOW,
    COLUMN_PROFILING_ENABLED,
} from './common';
import {
    SNOWFLAKE_ACCOUNT_ID,
    SNOWFLAKE_WAREHOUSE,
    SNOWFLAKE_USERNAME,
    SNOWFLAKE_PASSWORD,
    SNOWFLAKE_ROLE,
} from './snowflake';
import { BIGQUERY_PRIVATE_KEY, BIGQUERY_PRIVATE_KEY_ID, BIGQUERY_CLIENT_EMAIL, BIGQUERY_CLIENT_ID } from './bigquery';
import { REDSHIFT_HOST_PORT, REDSHIFT_DATABASE, REDSHIFT_USERNAME, REDSHIFT_PASSWORD } from './redshift';
import {
    TABLEAU_CONNECTION_URI,
    TABLEAU_PROJECT,
    TABLEAU_SITE,
    TABLEAU_USERNAME,
    TABLEAU_PASSWORD,
    TABLEAU_TOKEN_NAME,
    TABLEAU_TOKEN_VALUE,
} from './tableau';
import {
    CHART_ALLOW,
    CHART_DENY,
    DASHBOARD_ALLOW as LOOKER_DASHBOARD_ALLOW,
    DASHBOARD_DENY as LOOKER_DASHBOARD_DENY,
    LOOKER_BASE_URL,
    LOOKER_CLIENT_ID,
    LOOKER_CLIENT_SECRET,
} from './looker';
import {
    KAFKA_SASL_USERNAME,
    KAFKA_SASL_PASSWORD,
    KAFKA_BOOTSTRAP,
    KAFKA_SCHEMA_REGISTRY_URL,
    KAFKA_SCHEMA_REGISTRY_USER_CREDENTIAL,
    KAFKA_SECURITY_PROTOCOL,
    KAFKA_SASL_MECHANISM,
    TOPIC_ALLOW,
    TOPIC_DENY,
} from './kafka';
import { POSTGRES } from '../../conf/postgres/postgres';
import { POSTGRES_HOST_PORT, POSTGRES_DATABASE, POSTGRES_USERNAME, POSTGRES_PASSWORD } from './postgres';
import { HIVE } from '../../conf/hive/hive';
import { HIVE_HOST_PORT, HIVE_DATABASE, HIVE_USERNAME, HIVE_PASSWORD } from './hive';
import {
    LOOKML,
    CONNECTION_TO_PLATFORM_MAP,
    DEPLOY_KEY,
    LOOKML_BASE_URL,
    LOOKML_CLIENT_ID,
    LOOKML_CLIENT_SECRET,
    LOOKML_GITHUB_INFO_REPO,
    PARSE_TABLE_NAMES_FROM_SQL,
    PROJECT_NAME,
} from './lookml';
import { PRESTO, PRESTO_HOST_PORT, PRESTO_DATABASE, PRESTO_USERNAME, PRESTO_PASSWORD } from './presto';
import { AZURE, BIGQUERY_BETA, CSV, DBT_CLOUD, MYSQL, OKTA, POWER_BI, UNITY_CATALOG, VERTICA } from '../constants';
import { BIGQUERY_BETA_PROJECT_ID, DATASET_ALLOW, DATASET_DENY, PROJECT_ALLOW, PROJECT_DENY } from './bigqueryBeta';
import { MYSQL_HOST_PORT, MYSQL_PASSWORD, MYSQL_USERNAME } from './mysql';
import { MSSQL, MSSQL_DATABASE, MSSQL_HOST_PORT, MSSQL_PASSWORD, MSSQL_USERNAME } from './mssql';
import { TRINO, TRINO_DATABASE, TRINO_HOST_PORT, TRINO_PASSWORD, TRINO_USERNAME } from './trino';
import { MARIADB, MARIADB_DATABASE, MARIADB_HOST_PORT, MARIADB_PASSWORD, MARIADB_USERNAME } from './mariadb';
import {
    INCLUDE_COLUMN_LINEAGE,
    TOKEN,
    UNITY_CATALOG_ALLOW,
    UNITY_CATALOG_DENY,
    UNITY_METASTORE_ID_ALLOW,
    UNITY_METASTORE_ID_DENY,
    UNITY_TABLE_ALLOW,
    UNITY_TABLE_DENY,
    WORKSPACE_URL,
} from './unity_catalog';
import {
    DBT_CLOUD_ACCOUNT_ID,
    DBT_CLOUD_JOB_ID,
    DBT_CLOUD_PROJECT_ID,
    INCLUDE_MODELS,
    INCLUDE_SEEDS,
    INCLUDE_SOURCES,
    INCLUDE_TEST_DEFINITIONS,
    INCLUDE_TEST_RESULTS,
    EXTRACT_OWNERS as DBT_EXTRACT_OWNERS,
    NODE_ALLOW,
    NODE_DENY,
    TARGET_PLATFORM,
    TARGET_PLATFORM_INSTANCE,
    DBT_CLOUD_TOKEN,
} from './dbt_cloud';
import {
    ADMIN_APIS_ONLY,
    EXTRACT_ENDORSEMENTS_AS_TAGS,
    EXTRACT_OWNERSHIP,
    INCLUDE_REPORTS,
    INCLUDE_POWERBI_LINEAGE,
    INCLUDE_WORKSPACES,
    POWERBI_CLIENT_ID,
    POWERBI_CLIENT_SECRET,
    POWERBI_TENANT_ID,
    WORKSPACE_ID_ALLOW,
    WORKSPACE_ID_DENY,
} from './powerbi';

import {
    VERTICA_HOST_PORT,
    VERTICA_DATABASE,
    VERTICA_USERNAME,
    VERTICA_PASSWORD,
    INCLUDE_PROJECTIONS,
    INCLUDE_MLMODELS,
    INCLUDE_VIEW_LINEAGE,
    INCLUDE_PROJECTIONS_LINEAGE,
} from './vertica';
import { CSV_ARRAY_DELIMITER, CSV_DELIMITER, CSV_FILE_URL, CSV_WRITE_SEMANTICS } from './csv';
import {
    INCLUDE_DEPROVISIONED_USERS,
    INCLUDE_SUSPENDED_USERS,
    INGEST_GROUPS,
    INGEST_USERS,
    OKTA_API_TOKEN,
    OKTA_DOMAIN_URL,
    POFILE_TO_GROUP,
    POFILE_TO_GROUP_REGX_ALLOW,
    POFILE_TO_GROUP_REGX_DENY,
    POFILE_TO_USER,
    POFILE_TO_USER_REGX_ALLOW,
    POFILE_TO_USER_REGX_DENY,
    SKIP_USERS_WITHOUT_GROUP,
} from './okta';
import {
    AZURE_AUTHORITY_URL,
    AZURE_CLIENT_ID,
    AZURE_CLIENT_SECRET,
    AZURE_GRAPH_URL,
    AZURE_INGEST_GROUPS,
    AZURE_INGEST_USERS,
    AZURE_REDIRECT_URL,
    AZURE_TENANT_ID,
    AZURE_TOKEN_URL,
    GROUP_ALLOW,
    GROUP_DENY,
    USER_ALLOW,
    USER_DENY,
} from './azure';

export enum RecipeSections {
    Connection = 0,
    Filter = 1,
    Advanced = 2,
}

interface RecipeFields {
    [key: string]: {
        fields: RecipeField[];
        filterFields: RecipeField[];
        advancedFields: RecipeField[];
        connectionSectionTooltip?: string;
        filterSectionTooltip?: string;
        advancedSectionTooltip?: string;
        defaultOpenSections?: RecipeSections[];
    };
}

export const RECIPE_FIELDS: RecipeFields = {
    [SNOWFLAKE]: {
        fields: [SNOWFLAKE_ACCOUNT_ID, SNOWFLAKE_WAREHOUSE, SNOWFLAKE_USERNAME, SNOWFLAKE_PASSWORD, SNOWFLAKE_ROLE],
        advancedFields: [
            INCLUDE_TABLES,
            INCLUDE_VIEWS,
            INCLUDE_LINEAGE,
            TABLE_PROFILING_ENABLED,
            COLUMN_PROFILING_ENABLED,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterFields: [
            DATABASE_ALLOW,
            DATABASE_DENY,
            SCHEMA_ALLOW,
            SCHEMA_DENY,
            TABLE_ALLOW,
            TABLE_DENY,
            VIEW_ALLOW,
            VIEW_DENY,
        ],
        filterSectionTooltip: 'Include or exclude specific Databases, Schemas, Tables and Views from ingestion.',
    },
    [BIGQUERY]: {
        fields: [
            BIGQUERY_BETA_PROJECT_ID,
            BIGQUERY_PRIVATE_KEY,
            BIGQUERY_PRIVATE_KEY_ID,
            BIGQUERY_CLIENT_EMAIL,
            BIGQUERY_CLIENT_ID,
        ],
        advancedFields: [
            INCLUDE_TABLES,
            INCLUDE_VIEWS,
            INCLUDE_TABLE_LINEAGE,
            TABLE_PROFILING_ENABLED,
            COLUMN_PROFILING_ENABLED,
            START_TIME,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterFields: [
            PROJECT_ALLOW,
            PROJECT_DENY,
            DATASET_ALLOW,
            DATASET_DENY,
            TABLE_ALLOW,
            TABLE_DENY,
            VIEW_ALLOW,
            VIEW_DENY,
        ],
        filterSectionTooltip: 'Include or exclude specific Projects, Datasets, Tables and Views from ingestion.',
    },
    [BIGQUERY_BETA]: {
        fields: [
            BIGQUERY_BETA_PROJECT_ID,
            BIGQUERY_PRIVATE_KEY,
            BIGQUERY_PRIVATE_KEY_ID,
            BIGQUERY_CLIENT_EMAIL,
            BIGQUERY_CLIENT_ID,
        ],
        advancedFields: [
            INCLUDE_TABLE_LINEAGE,
            TABLE_PROFILING_ENABLED,
            COLUMN_PROFILING_ENABLED,
            START_TIME,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterFields: [
            PROJECT_ALLOW,
            PROJECT_DENY,
            DATASET_ALLOW,
            DATASET_DENY,
            TABLE_ALLOW,
            TABLE_DENY,
            VIEW_ALLOW,
            VIEW_DENY,
        ],
        filterSectionTooltip: 'Include or exclude specific Projects, Datasets, Tables and Views from ingestion.',
    },
    [REDSHIFT]: {
        fields: [REDSHIFT_HOST_PORT, REDSHIFT_DATABASE, REDSHIFT_USERNAME, REDSHIFT_PASSWORD],
        advancedFields: [
            INCLUDE_TABLES,
            INCLUDE_VIEWS,
            INCLUDE_TABLE_LINEAGE,
            TABLE_PROFILING_ENABLED,
            COLUMN_PROFILING_ENABLED,
            TABLE_LINEAGE_MODE,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterFields: [SCHEMA_ALLOW, SCHEMA_DENY, TABLE_ALLOW, TABLE_DENY, VIEW_ALLOW, VIEW_DENY],
        filterSectionTooltip: 'Include or exclude specific Schemas, Tables and Views from ingestion.',
    },
    [TABLEAU]: {
        fields: [
            TABLEAU_CONNECTION_URI,
            TABLEAU_PROJECT,
            TABLEAU_SITE,
            TABLEAU_TOKEN_NAME,
            TABLEAU_TOKEN_VALUE,
            STATEFUL_INGESTION_ENABLED,
            TABLEAU_USERNAME,
            TABLEAU_PASSWORD,
        ],
        filterFields: [],
        advancedFields: [INGEST_TAGS, INGEST_OWNER],
    },
    [LOOKER]: {
        fields: [LOOKER_BASE_URL, LOOKER_CLIENT_ID, LOOKER_CLIENT_SECRET],
        filterFields: [LOOKER_DASHBOARD_ALLOW, LOOKER_DASHBOARD_DENY, CHART_ALLOW, CHART_DENY],
        advancedFields: [EXTRACT_USAGE_HISTORY, EXTRACT_OWNERS, SKIP_PERSONAL_FOLDERS, STATEFUL_INGESTION_ENABLED],
        filterSectionTooltip: 'Include or exclude specific Dashboard, Charts from Looker ingestion.',
    },
    [LOOKML]: {
        fields: [
            LOOKML_GITHUB_INFO_REPO,
            DEPLOY_KEY,
            PROJECT_NAME,
            LOOKML_BASE_URL,
            LOOKML_CLIENT_ID,
            LOOKML_CLIENT_SECRET,
        ],
        filterFields: [],
        advancedFields: [PARSE_TABLE_NAMES_FROM_SQL, CONNECTION_TO_PLATFORM_MAP, STATEFUL_INGESTION_ENABLED],
        advancedSectionTooltip:
            'In order to ingest LookML data properly, you must either fill out Looker API client information (Base URL, Client ID, Client Secret) or an offline specification of the connection to platform mapping and the project name (Connection To Platform Map, Project Name).',
        defaultOpenSections: [RecipeSections.Connection, RecipeSections.Advanced],
    },
    [KAFKA]: {
        fields: [
            KAFKA_SECURITY_PROTOCOL,
            KAFKA_SASL_MECHANISM,
            KAFKA_SASL_USERNAME,
            KAFKA_SASL_PASSWORD,
            KAFKA_BOOTSTRAP,
            KAFKA_SCHEMA_REGISTRY_URL,
            KAFKA_SCHEMA_REGISTRY_USER_CREDENTIAL,
        ],
        filterFields: [TOPIC_ALLOW, TOPIC_DENY],
        advancedFields: [STATEFUL_INGESTION_ENABLED],
        filterSectionTooltip:
            'Filter out data assets based on allow/deny regex patterns we match against. Deny patterns take precedence over allow patterns.',
    },
    [POSTGRES]: {
        fields: [POSTGRES_HOST_PORT, POSTGRES_USERNAME, POSTGRES_PASSWORD, POSTGRES_DATABASE],
        filterFields: [SCHEMA_ALLOW, SCHEMA_DENY, TABLE_ALLOW, TABLE_DENY, VIEW_ALLOW, VIEW_DENY],
        advancedFields: [
            INCLUDE_TABLES,
            INCLUDE_VIEWS,
            TABLE_PROFILING_ENABLED,
            COLUMN_PROFILING_ENABLED,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterSectionTooltip: 'Include or exclude specific Schemas, Tables and Views from ingestion.',
    },
    [MYSQL]: {
        fields: [MYSQL_HOST_PORT, MYSQL_USERNAME, MYSQL_PASSWORD],
        filterFields: [
            DATABASE_ALLOW,
            DATABASE_DENY,
            SCHEMA_ALLOW,
            SCHEMA_DENY,
            TABLE_ALLOW,
            TABLE_DENY,
            VIEW_ALLOW,
            VIEW_DENY,
        ],
        advancedFields: [
            INCLUDE_TABLES,
            INCLUDE_VIEWS,
            TABLE_PROFILING_ENABLED,
            COLUMN_PROFILING_ENABLED,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterSectionTooltip: 'Include or exclude specific Databases, Schemas, Tables and Views from ingestion.',
    },
    [HIVE]: {
        fields: [HIVE_HOST_PORT, HIVE_USERNAME, HIVE_PASSWORD, HIVE_DATABASE],
        filterFields: [SCHEMA_ALLOW, SCHEMA_DENY, TABLE_ALLOW, TABLE_DENY, VIEW_ALLOW, VIEW_DENY],
        advancedFields: [INCLUDE_TABLES, TABLE_PROFILING_ENABLED, COLUMN_PROFILING_ENABLED, STATEFUL_INGESTION_ENABLED],
        filterSectionTooltip: 'Include or exclude specific Schemas, Tables and Views from ingestion.',
    },
    [PRESTO]: {
        fields: [PRESTO_HOST_PORT, PRESTO_USERNAME, PRESTO_PASSWORD, PRESTO_DATABASE],
        filterFields: [SCHEMA_ALLOW, SCHEMA_DENY, TABLE_ALLOW, TABLE_DENY, VIEW_ALLOW, VIEW_DENY],
        advancedFields: [
            INCLUDE_TABLES,
            INCLUDE_VIEWS,
            TABLE_PROFILING_ENABLED,
            COLUMN_PROFILING_ENABLED,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterSectionTooltip: 'Include or exclude specific Schemas, Tables and Views from ingestion.',
    },
    [MSSQL]: {
        fields: [MSSQL_HOST_PORT, MSSQL_USERNAME, MSSQL_PASSWORD, MSSQL_DATABASE],
        filterFields: [SCHEMA_ALLOW, SCHEMA_DENY, TABLE_ALLOW, TABLE_DENY, VIEW_ALLOW, VIEW_DENY],
        advancedFields: [
            INCLUDE_TABLES,
            INCLUDE_VIEWS,
            TABLE_PROFILING_ENABLED,
            COLUMN_PROFILING_ENABLED,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterSectionTooltip: 'Include or exclude specific Schemas, Tables and Views from ingestion.',
    },
    [TRINO]: {
        fields: [TRINO_HOST_PORT, TRINO_USERNAME, TRINO_PASSWORD, TRINO_DATABASE],
        filterFields: [SCHEMA_ALLOW, SCHEMA_DENY, TABLE_ALLOW, TABLE_DENY, VIEW_ALLOW, VIEW_DENY],
        advancedFields: [
            INCLUDE_TABLES,
            INCLUDE_VIEWS,
            TABLE_PROFILING_ENABLED,
            COLUMN_PROFILING_ENABLED,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterSectionTooltip: 'Include or exclude specific Schemas, Tables and Views from ingestion.',
    },
    [MARIADB]: {
        fields: [MARIADB_HOST_PORT, MARIADB_USERNAME, MARIADB_PASSWORD, MARIADB_DATABASE],
        filterFields: [SCHEMA_ALLOW, SCHEMA_DENY, TABLE_ALLOW, TABLE_DENY, VIEW_ALLOW, VIEW_DENY],
        advancedFields: [
            INCLUDE_TABLES,
            INCLUDE_VIEWS,
            TABLE_PROFILING_ENABLED,
            COLUMN_PROFILING_ENABLED,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterSectionTooltip: 'Include or exclude specific Schemas, Tables and Views from ingestion.',
    },
    [UNITY_CATALOG]: {
        fields: [WORKSPACE_URL, TOKEN],
        filterFields: [
            UNITY_METASTORE_ID_ALLOW,
            UNITY_METASTORE_ID_DENY,
            UNITY_CATALOG_ALLOW,
            UNITY_CATALOG_DENY,
            SCHEMA_ALLOW,
            SCHEMA_DENY,
            UNITY_TABLE_ALLOW,
            UNITY_TABLE_DENY,
        ],
        advancedFields: [INCLUDE_TABLE_LINEAGE, INCLUDE_COLUMN_LINEAGE, STATEFUL_INGESTION_ENABLED],
        filterSectionTooltip: 'Include or exclude specific Metastores, Catalogs, Schemas, and Tables from ingestion.',
    },
    [DBT_CLOUD]: {
        fields: [
            DBT_CLOUD_ACCOUNT_ID,
            DBT_CLOUD_PROJECT_ID,
            DBT_CLOUD_JOB_ID,
            DBT_CLOUD_TOKEN,
            TARGET_PLATFORM,
            TARGET_PLATFORM_INSTANCE,
        ],
        filterFields: [NODE_ALLOW, NODE_DENY],
        advancedFields: [
            INCLUDE_MODELS,
            INCLUDE_SOURCES,
            INCLUDE_SEEDS,
            INCLUDE_TEST_DEFINITIONS,
            INCLUDE_TEST_RESULTS,
            DBT_EXTRACT_OWNERS,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterSectionTooltip: 'Include or exclude specific dbt Node (resources) from ingestion.',
    },
    [POWER_BI]: {
        fields: [POWERBI_TENANT_ID, POWERBI_CLIENT_ID, POWERBI_CLIENT_SECRET],
        filterFields: [WORKSPACE_ID_ALLOW, WORKSPACE_ID_DENY],
        advancedFields: [
            INCLUDE_WORKSPACES,
            INCLUDE_REPORTS,
            INCLUDE_POWERBI_LINEAGE,
            EXTRACT_OWNERSHIP,
            EXTRACT_ENDORSEMENTS_AS_TAGS,
            ADMIN_APIS_ONLY,
            STATEFUL_INGESTION_ENABLED,
        ],
        filterSectionTooltip: 'Include or exclude specific PowerBI Workspaces from ingestion.',
    },
    [VERTICA]: {
        fields: [VERTICA_HOST_PORT, VERTICA_DATABASE, VERTICA_USERNAME, VERTICA_PASSWORD],
        filterFields: [SCHEMA_ALLOW, SCHEMA_DENY, TABLE_ALLOW, TABLE_DENY, VIEW_ALLOW, VIEW_DENY],
        advancedFields: [
            INCLUDE_TABLES,
            INCLUDE_VIEWS,
            INCLUDE_PROJECTIONS,
            INCLUDE_MLMODELS,
            INCLUDE_VIEW_LINEAGE,
            INCLUDE_PROJECTIONS_LINEAGE,
            TABLE_PROFILING_ENABLED,
        ],
        filterSectionTooltip: 'Include or exclude specific Schemas, Tables, Views and Projections from ingestion.',
    },
    [CSV]: {
        fields: [CSV_FILE_URL],
        filterFields: [],
        advancedFields: [CSV_ARRAY_DELIMITER, CSV_DELIMITER, CSV_WRITE_SEMANTICS],
    },
    [OKTA]: {
        fields: [OKTA_DOMAIN_URL, OKTA_API_TOKEN, POFILE_TO_USER, POFILE_TO_GROUP],
        filterFields: [
            POFILE_TO_USER_REGX_ALLOW,
            POFILE_TO_USER_REGX_DENY,
            POFILE_TO_GROUP_REGX_ALLOW,
            POFILE_TO_GROUP_REGX_DENY,
        ],
        advancedFields: [
            INGEST_USERS,
            INGEST_GROUPS,
            INCLUDE_DEPROVISIONED_USERS,
            INCLUDE_SUSPENDED_USERS,
            STATEFUL_INGESTION_ENABLED,
            SKIP_USERS_WITHOUT_GROUP,
        ],
    },
    [AZURE]: {
        fields: [
            AZURE_CLIENT_ID,
            AZURE_TENANT_ID,
            AZURE_CLIENT_SECRET,
            AZURE_REDIRECT_URL,
            AZURE_AUTHORITY_URL,
            AZURE_TOKEN_URL,
            AZURE_GRAPH_URL,
        ],
        filterFields: [GROUP_ALLOW, GROUP_DENY, USER_ALLOW, USER_DENY],
        advancedFields: [AZURE_INGEST_USERS, AZURE_INGEST_GROUPS, STATEFUL_INGESTION_ENABLED, SKIP_USERS_WITHOUT_GROUP],
    },
};

export const CONNECTORS_WITH_FORM = new Set(Object.keys(RECIPE_FIELDS));

export const CONNECTORS_WITH_TEST_CONNECTION = new Set([SNOWFLAKE, LOOKER, BIGQUERY_BETA, BIGQUERY, UNITY_CATALOG]);
