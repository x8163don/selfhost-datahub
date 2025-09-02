import datetime
import logging
import re
import sys
from typing import Any, Dict, List, Optional, cast
from unittest import mock
from unittest.mock import MagicMock

import pytest
from freezegun import freeze_time

from datahub.ingestion.run.pipeline import Pipeline
from datahub.ingestion.source.powerbi.config import (
    Constant,
    PowerBiDashboardSourceConfig,
    SupportedDataPlatform,
)
from datahub.ingestion.source.powerbi.powerbi import PowerBiDashboardSource
from datahub.ingestion.source.powerbi.rest_api_wrapper.data_classes import (
    Page,
    Report,
    Workspace,
)
from tests.test_helpers import mce_helpers, test_connection_helpers

pytestmark = pytest.mark.integration_batch_2
FROZEN_TIME = "2022-02-03 07:00:00"


def enable_logging():
    # set logging to console
    logging.getLogger().addHandler(logging.StreamHandler(sys.stdout))
    logging.getLogger().setLevel(logging.DEBUG)


class MsalClient:
    call_num = 0
    token: Dict[str, Any] = {
        "access_token": "dummy",
    }

    @staticmethod
    def acquire_token_for_client(*args, **kwargs):
        MsalClient.call_num += 1
        return MsalClient.token

    @staticmethod
    def reset():
        MsalClient.call_num = 0


def mock_msal_cca(*args, **kwargs):
    return MsalClient()


def scan_init_response(request, context):
    # Request mock is passing POST input in the form of workspaces=<workspace_id>
    # If we scan 2 or more, it get messy like this. 'workspaces=64ED5CAD-7C10-4684-8180-826122881108&workspaces=64ED5CAD-7C22-4684-8180-826122881108'
    workspace_id_list = request.text.replace("&", "").split("workspaces=")

    workspace_id = "||".join(workspace_id_list[1:])

    w_id_vs_response: Dict[str, Any] = {
        "64ED5CAD-7C10-4684-8180-826122881108": {
            "id": "4674efd1-603c-4129-8d82-03cf2be05aff"
        },
        "64ED5CAD-7C22-4684-8180-826122881108": {
            "id": "a674efd1-603c-4129-8d82-03cf2be05aff"
        },
        "64ED5CAD-7C10-4684-8180-826122881108||64ED5CAD-7C22-4684-8180-826122881108": {
            "id": "a674efd1-603c-4129-8d82-03cf2be05aff"
        },
    }

    return w_id_vs_response[workspace_id]


def register_mock_api(request_mock: Any, override_data: Optional[dict] = None) -> None:
    override_data = override_data or {}
    api_vs_response = {
        "https://api.powerbi.com/v1.0/myorg/groups": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "@odata.count": 3,
                "value": [
                    {
                        "id": "64ED5CAD-7C10-4684-8180-826122881108",
                        "isReadOnly": True,
                        "name": "demo-workspace",
                        "type": "Workspace",
                    },
                    {
                        "id": "64ED5CAD-7C22-4684-8180-826122881108",
                        "isReadOnly": True,
                        "name": "second-demo-workspace",
                        "type": "Workspace",
                    },
                    {
                        "id": "64ED5CAD-7322-4684-8180-826122881108",
                        "isReadOnly": True,
                        "name": "Workspace 2",
                        "type": "Workspace",
                    },
                ],
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/dashboards": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "value": [
                    {
                        "id": "7D668CAD-7FFC-4505-9215-655BCA5BEBAE",
                        "isReadOnly": True,
                        "displayName": "test_dashboard",
                        "description": "Description of test dashboard",
                        "embedUrl": "https://localhost/dashboards/embed/1",
                        "webUrl": "https://localhost/dashboards/web/1",
                    }
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C22-4684-8180-826122881108/dashboards": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "value": [
                    {
                        "id": "7D668CAD-8FFC-4505-9215-655BCA5BEBAE",
                        "isReadOnly": True,
                        "displayName": "test_dashboard2",
                        "embedUrl": "https://localhost/dashboards/embed/1",
                        "webUrl": "https://localhost/dashboards/web/1",
                    }
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/admin/reports/5b218778-e7a5-4d73-8187-f10824047715/users": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "value": [
                    {
                        "identifier": "User1@foo.com",
                        "displayName": "user1",
                        "emailAddress": "User1@foo.com",
                        "datasetUserAccessRight": "ReadWrite",
                        "graphId": "C9EE53F2-88EA-4711-A173-AF0515A3CD46",
                        "principalType": "User",
                    },
                    {
                        "identifier": "User2@foo.com",
                        "displayName": "user2",
                        "emailAddress": "User2@foo.com",
                        "datasetUserAccessRight": "ReadWrite",
                        "graphId": "C9EE53F2-88EA-4711-A173-AF0515A5REWS",
                        "principalType": "User",
                    },
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/admin/dashboards/7D668CAD-7FFC-4505-9215-655BCA5BEBAE/users": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "value": [
                    {
                        "identifier": "User1@foo.com",
                        "displayName": "user1",
                        "emailAddress": "User1@foo.com",
                        "datasetUserAccessRight": "ReadWrite",
                        "graphId": "C9EE53F2-88EA-4711-A173-AF0515A3CD46",
                        "principalType": "User",
                    },
                    {
                        "identifier": "User2@foo.com",
                        "displayName": "user2",
                        "emailAddress": "User2@foo.com",
                        "datasetUserAccessRight": "ReadWrite",
                        "graphId": "C9EE53F2-88EA-4711-A173-AF0515A5REWS",
                        "principalType": "User",
                    },
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/admin/dashboards/7D668CAD-8FFC-4505-9215-655BCA5BEBAE/users": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "value": [
                    {
                        "identifier": "User3@foo.com",
                        "displayName": "user3",
                        "emailAddress": "User3@foo.com",
                        "datasetUserAccessRight": "ReadWrite",
                        "graphId": "C9EE53F2-88EA-4711-A173-AF0515A3CD46",
                        "principalType": "User",
                    },
                    {
                        "identifier": "User4@foo.com",
                        "displayName": "user4",
                        "emailAddress": "User4@foo.com",
                        "datasetUserAccessRight": "ReadWrite",
                        "graphId": "C9EE53F2-88EA-4711-A173-AF0515A5REWS",
                        "principalType": "User",
                    },
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/dashboards/7D668CAD-7FFC-4505-9215-655BCA5BEBAE/tiles": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "value": [
                    {
                        "id": "B8E293DC-0C83-4AA0-9BB9-0A8738DF24A0",
                        "title": "test_tile",
                        "embedUrl": "https://localhost/tiles/embed/1",
                        "datasetId": "05169CD2-E713-41E6-9600-1D8066D95445",
                    },
                    {
                        "id": "23212598-23b5-4980-87cc-5fc0ecd84385",
                        "title": "yearly_sales",
                        "embedUrl": "https://localhost/tiles/embed/2",
                        "datasetId": "ba0130a1-5b03-40de-9535-b34e778ea6ed",
                    },
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C22-4684-8180-826122881108/dashboards/7D668CAD-8FFC-4505-9215-655BCA5BEBAE/tiles": {
            "method": "GET",
            "status_code": 200,
            "json": {"value": []},
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/datasets/05169CD2-E713-41E6-9600-1D8066D95445": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "id": "05169CD2-E713-41E6-9600-1D8066D95445",
                "name": "library-dataset",
                "description": "Library dataset description",
                "webUrl": "http://localhost/groups/64ED5CAD-7C10-4684-8180-826122881108/datasets/05169CD2-E713-41E6-9600-1D8066D95445",
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C22-4684-8180-826122881108/datasets/05169CD2-E713-41E6-96AA-1D8066D95445": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "id": "05169CD2-E713-41E6-96AA-1D8066D95445",
                "name": "library-dataset",
                "description": "Library dataset description",
                "webUrl": "http://localhost/groups/64ED5CAD-7C22-4684-8180-826122881108/datasets/05169CD2-E713-41E6-96AA-1D8066D95445",
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/datasets/ba0130a1-5b03-40de-9535-b34e778ea6ed": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "id": "ba0130a1-5b03-40de-9535-b34e778ea6ed",
                "name": "hr_pbi_test",
                "description": "hr pbi test description",
                "webUrl": "http://localhost/groups/64ED5CAD-7C10-4684-8180-826122881108/datasets/ba0130a1-5b03-40de-9535-b34e778ea6ed",
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/datasets/05169CD2-E713-41E6-9600-1D8066D95445/datasources": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "value": [
                    {
                        "datasourceId": "DCE90B40-84D6-467A-9A5C-648E830E72D3",
                        "datasourceType": "PostgreSql",
                        "connectionDetails": {
                            "database": "library_db",
                            "server": "foo",
                        },
                    },
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C22-4684-8180-826122881108/datasets/05169CD2-E713-41E6-96AA-1D8066D95445/datasources": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "value": [
                    {
                        "datasourceId": "DCE90B40-84D6-467A-9A5C-648E830E72D3",
                        "datasourceType": "PostgreSql",
                        "connectionDetails": {
                            "database": "library_db",
                            "server": "foo",
                        },
                    },
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/admin/workspaces/scanStatus/4674efd1-603c-4129-8d82-03cf2be05aff": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "status": "SUCCEEDED",
            },
        },
        "https://api.powerbi.com/v1.0/myorg/admin/workspaces/scanStatus/a674efd1-603c-4129-8d82-03cf2be05aff": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "status": "SUCCEEDED",
            },
        },
        "https://api.powerbi.com/v1.0/myorg/admin/workspaces/scanResult/4674efd1-603c-4129-8d82-03cf2be05aff": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "workspaces": [
                    {
                        "id": "64ED5CAD-7C10-4684-8180-826122881108",
                        "name": "demo-workspace",
                        "state": "Active",
                        "datasets": [
                            {
                                "id": "05169CD2-E713-41E6-9600-1D8066D95445",
                                "endorsementDetails": {"endorsement": "Promoted"},
                                "name": "test_sf_pbi_test",
                                "tables": [
                                    {
                                        "name": "public issue_history",
                                        "source": [
                                            {
                                                "expression": "dummy",
                                            }
                                        ],
                                        "datasourceUsages": [
                                            {
                                                "datasourceInstanceId": "DCE90B40-84D6-467A-9A5C-648E830E72D3",
                                            }
                                        ],
                                    },
                                    {
                                        "name": "SNOWFLAKE_TESTTABLE",
                                        "source": [
                                            {
                                                "expression": 'let\n    Source = Snowflake.Databases("hp123rt5.ap-southeast-2.fakecomputing.com","PBI_TEST_WAREHOUSE_PROD",[Role="PBI_TEST_MEMBER"]),\n    PBI_TEST_Database = Source{[Name="PBI_TEST",Kind="Database"]}[Data],\n    TEST_Schema = PBI_TEST_Database{[Name="TEST",Kind="Schema"]}[Data],\n    TESTTABLE_Table = TEST_Schema{[Name="TESTTABLE",Kind="Table"]}[Data]\nin\n    TESTTABLE_Table',
                                            }
                                        ],
                                        "datasourceUsages": [
                                            {
                                                "datasourceInstanceId": "DCE90B40-84D6-467A-9A5C-648E830E72D3",
                                            }
                                        ],
                                    },
                                    {
                                        "name": "snowflake native-query",
                                        "source": [
                                            {
                                                "expression": 'let\n    Source = Value.NativeQuery(Snowflake.Databases("bu20658.ap-southeast-2.snowflakecomputing.com","operations_analytics_warehouse_prod",[Role="OPERATIONS_ANALYTICS_MEMBER"]){[Name="OPERATIONS_ANALYTICS"]}[Data], "SELECT#(lf)concat((UPPER(REPLACE(SELLER,\'-\',\'\'))), MONTHID) as AGENT_KEY,#(lf)concat((UPPER(REPLACE(CLIENT_DIRECTOR,\'-\',\'\'))), MONTHID) as CD_AGENT_KEY,#(lf) *#(lf)FROM#(lf)OPERATIONS_ANALYTICS.TRANSFORMED_PROD.V_APS_SME_UNITS_V4", null, [EnableFolding=true]),\n    #"Added Conditional Column" = Table.AddColumn(Source, "SME Units ENT", each if [DEAL_TYPE] = "SME Unit" then [UNIT] else 0),\n    #"Added Conditional Column1" = Table.AddColumn(#"Added Conditional Column", "Banklink Units", each if [DEAL_TYPE] = "Banklink" then [UNIT] else 0),\n    #"Removed Columns" = Table.RemoveColumns(#"Added Conditional Column1",{"Banklink Units"}),\n    #"Added Custom" = Table.AddColumn(#"Removed Columns", "Banklink Units", each if [DEAL_TYPE] = "Banklink" and [SALES_TYPE] = "3 - Upsell"\nthen [UNIT]\n\nelse if [SALES_TYPE] = "Adjusted BL Migration"\nthen [UNIT]\n\nelse 0),\n    #"Added Custom1" = Table.AddColumn(#"Added Custom", "SME Units in $ (*$361)", each if [DEAL_TYPE] = "SME Unit" \nand [SALES_TYPE] <> "4 - Renewal"\n    then [UNIT] * 361\nelse 0),\n    #"Added Custom2" = Table.AddColumn(#"Added Custom1", "Banklink in $ (*$148)", each [Banklink Units] * 148)\nin\n    #"Added Custom2"',
                                            }
                                        ],
                                        "datasourceUsages": [
                                            {
                                                "datasourceInstanceId": "DCE90B40-84D6-467A-9A5C-648E830E72D3",
                                            }
                                        ],
                                    },
                                    {
                                        "name": "big-query-with-parameter",
                                        "source": [
                                            {
                                                "expression": 'let\n Source = GoogleBigQuery.Database([BillingProject = #"Parameter - Source"]),\n#"gcp-project" = Source{[Name=#"Parameter - Source"]}[Data],\nuniversal_Schema = #"gcp-project"{[Name="universal",Kind="Schema"]}[Data],\nD_WH_DATE_Table = universal_Schema{[Name="D_WH_DATE",Kind="Table"]}[Data],\n#"Filtered Rows" = Table.SelectRows(D_WH_DATE_Table, each [D_DATE] > #datetime(2019, 9, 10, 0, 0, 0)),\n#"Filtered Rows1" = Table.SelectRows(#"Filtered Rows", each DateTime.IsInPreviousNHours([D_DATE], 87600))\n in \n#"Filtered Rows1"',
                                            }
                                        ],
                                        "datasourceUsages": [
                                            {
                                                "datasourceInstanceId": "DCE90B40-84D6-467A-9A5C-648E830E72D3",
                                            }
                                        ],
                                    },
                                    {
                                        "name": "snowflake native-query-with-join",
                                        "source": [
                                            {
                                                "expression": 'let\n    Source = Value.NativeQuery(Snowflake.Databases("xaa48144.snowflakecomputing.com","GSL_TEST_WH",[Role="ACCOUNTADMIN"]){[Name="GSL_TEST_DB"]}[Data], "select A.name from GSL_TEST_DB.PUBLIC.SALES_ANALYST as A inner join GSL_TEST_DB.PUBLIC.SALES_FORECAST as B on A.name = B.name where startswith(A.name, \'mo\')", null, [EnableFolding=true])\nin\n    Source',
                                            }
                                        ],
                                        "datasourceUsages": [
                                            {
                                                "datasourceInstanceId": "DCE90B40-84D6-467A-9A5C-648E830E72D3",
                                            }
                                        ],
                                    },
                                    {
                                        "name": "job-history",
                                        "source": [
                                            {
                                                "expression": 'let\n    Source = Oracle.Database("localhost:1521/salesdb.GSLAB.COM", [HierarchicalNavigation=true]), HR = Source{[Schema="HR"]}[Data], EMPLOYEES1 = HR{[Name="EMPLOYEES"]}[Data] \n in EMPLOYEES1',
                                            }
                                        ],
                                        "datasourceUsages": [
                                            {
                                                "datasourceInstanceId": "DCE90B40-84D6-467A-9A5C-648E830E72D3",
                                            }
                                        ],
                                    },
                                    {
                                        "name": "postgres_test_table",
                                        "source": [
                                            {
                                                "expression": 'let\n    Source = PostgreSQL.Database("localhost"  ,   "mics"      ),\n  public_order_date =    Source{[Schema="public",Item="order_date"]}[Data] \n in \n public_order_date',
                                            }
                                        ],
                                        "datasourceUsages": [
                                            {
                                                "datasourceInstanceId": "DCE90B40-84D6-467A-9A5C-648E830E72D3",
                                            }
                                        ],
                                    },
                                ],
                            },
                            {
                                "id": "ba0130a1-5b03-40de-9535-b34e778ea6ed",
                                "name": "hr_pbi_test",
                                "tables": [
                                    {
                                        "name": "dbo_book_issue",
                                        "source": [
                                            {
                                                "expression": 'let\n    Source = Sql.Database("localhost", "library"),\n dbo_book_issue = Source{[Schema="dbo",Item="book_issue"]}[Data]\n in dbo_book_issue',
                                            }
                                        ],
                                        "datasourceUsages": [
                                            {
                                                "datasourceInstanceId": "DCE90B40-84D6-467A-9A5C-648E830E72D3",
                                            }
                                        ],
                                    },
                                    {
                                        "name": "ms_sql_native_table",
                                        "source": [
                                            {
                                                "expression": 'let\n    Source = Sql.Database("AUPRDWHDB", "COMMOPSDB", [Query="select *,#(lf)concat((UPPER(REPLACE(CLIENT_DIRECTOR,\'-\',\'\'))), MONTH_WID) as CD_AGENT_KEY,#(lf)concat((UPPER(REPLACE(CLIENT_MANAGER_CLOSING_MONTH,\'-\',\'\'))), MONTH_WID) as AGENT_KEY#(lf)#(lf)from V_PS_CD_RETENTION", CommandTimeout=#duration(0, 1, 30, 0)]),\n    #"Changed Type" = Table.TransformColumnTypes(Source,{{"mth_date", type date}}),\n    #"Added Custom" = Table.AddColumn(#"Changed Type", "Month", each Date.Month([mth_date])),\n    #"Added Custom1" = Table.AddColumn(#"Added Custom", "TPV Opening", each if [Month] = 1 then [TPV_AMV_OPENING]\nelse if [Month] = 2 then 0\nelse if [Month] = 3 then 0\nelse if [Month] = 4 then [TPV_AMV_OPENING]\nelse if [Month] = 5 then 0\nelse if [Month] = 6 then 0\nelse if [Month] = 7 then [TPV_AMV_OPENING]\nelse if [Month] = 8 then 0\nelse if [Month] = 9 then 0\nelse if [Month] = 10 then [TPV_AMV_OPENING]\nelse if [Month] = 11 then 0\nelse if [Month] = 12 then 0\n\nelse 0)\nin\n    #"Added Custom1"',
                                            }
                                        ],
                                        "datasourceUsages": [
                                            {
                                                "datasourceInstanceId": "DCE90B40-84D6-467A-9A5C-648E830E72D3",
                                            }
                                        ],
                                    },
                                ],
                            },
                            {
                                "id": "91580e0e-1680-4b1c-bbf9-4f6764d7a5ff",
                                "tables": [
                                    {
                                        "name": "employee_ctc",
                                        "source": [
                                            {
                                                "expression": "dummy",
                                            }
                                        ],
                                    }
                                ],
                            },
                        ],
                        "dashboards": [
                            {
                                "id": "7D668CAD-7FFC-4505-9215-655BCA5BEBAE",
                                "isReadOnly": True,
                            }
                        ],
                        "reports": [
                            {
                                "datasetId": "05169CD2-E713-41E6-9600-1D8066D95445",
                                "id": "5b218778-e7a5-4d73-8187-f10824047715",
                                "name": "SalesMarketing",
                                "description": "Acryl sales marketing report",
                            }
                        ],
                    },
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/admin/workspaces/scanResult/a674efd1-603c-4129-8d82-03cf2be05aff": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "workspaces": [
                    {
                        "id": "64ED5CAD-7C22-4684-8180-826122881108",
                        "name": "second-demo-workspace",
                        "state": "Active",
                        "datasets": [
                            {
                                "id": "05169CD2-E713-41E6-96AA-1D8066D95445",
                                "tables": [
                                    {
                                        "name": "public articles",
                                        "source": [
                                            {
                                                "expression": "dummy",
                                            }
                                        ],
                                    }
                                ],
                            }
                        ],
                        "dashboards": [
                            {
                                "id": "7D668CAD-8FFC-4505-9215-655BCA5BEBAE",
                                "isReadOnly": True,
                            }
                        ],
                        "reports": [
                            {
                                "datasetId": "05169CD2-E713-41E6-9600-1D8066D95445",
                                "id": "5b218778-e7a5-4d73-8187-f10824047715",
                                "name": "SalesMarketing",
                                "description": "Acryl sales marketing report",
                            }
                        ],
                    },
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/admin/workspaces/getInfo": {
            "method": "POST",
            "status_code": 200,
            "json": scan_init_response,
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/reports": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "value": [
                    {
                        "datasetId": "05169CD2-E713-41E6-9600-1D8066D95445",
                        "id": "5b218778-e7a5-4d73-8187-f10824047715",
                        "name": "SalesMarketing",
                        "description": "Acryl sales marketing report",
                        "webUrl": "https://app.powerbi.com/groups/f089354e-8366-4e18-aea3-4cb4a3a50b48/reports/5b218778-e7a5-4d73-8187-f10824047715",
                        "embedUrl": "https://app.powerbi.com/reportEmbed?reportId=5b218778-e7a5-4d73-8187-f10824047715&groupId=f089354e-8366-4e18-aea3-4cb4a3a50b48",
                    }
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/reports/5b218778-e7a5-4d73-8187-f10824047715": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "datasetId": "05169CD2-E713-41E6-9600-1D8066D95445",
                "id": "5b218778-e7a5-4d73-8187-f10824047715",
                "name": "SalesMarketing",
                "description": "Acryl sales marketing report",
                "webUrl": "https://app.powerbi.com/groups/f089354e-8366-4e18-aea3-4cb4a3a50b48/reports/5b218778-e7a5-4d73-8187-f10824047715",
                "embedUrl": "https://app.powerbi.com/reportEmbed?reportId=5b218778-e7a5-4d73-8187-f10824047715&groupId=f089354e-8366-4e18-aea3-4cb4a3a50b48",
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/reports/5b218778-e7a5-4d73-8187-f10824047715/pages": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "value": [
                    {
                        "displayName": "Regional Sales Analysis",
                        "name": "ReportSection",
                        "order": "0",
                    },
                    {
                        "displayName": "Geographic Analysis",
                        "name": "ReportSection1",
                        "order": "1",
                    },
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/datasets/05169CD2-E713-41E6-9600-1D8066D95445/parameters": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "value": [
                    {
                        "name": "Parameter - Source",
                        "type": "Text",
                        "isRequired": True,
                        "currentValue": "my-test-project",
                    },
                    {
                        "name": "My bq project",
                        "type": "Text",
                        "isRequired": True,
                        "currentValue": "gcp_billing",
                    },
                ]
            },
        },
        "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/datasets/91580e0e-1680-4b1c-bbf9-4f6764d7a5ff": {
            "method": "GET",
            "status_code": 200,
            "json": {
                "id": "91580e0e-1680-4b1c-bbf9-4f6764d7a5ff",
                "name": "employee-dataset",
                "description": "Employee Management",
                "webUrl": "http://localhost/groups/64ED5CAD-7C10-4684-8180-826122881108/datasets/91580e0e-1680-4b1c-bbf9-4f6764d7a5ff",
            },
        },
    }

    api_vs_response.update(override_data)

    for url in api_vs_response.keys():
        request_mock.register_uri(
            api_vs_response[url]["method"],
            url,
            json=api_vs_response[url]["json"],
            status_code=api_vs_response[url]["status_code"],
        )


def default_source_config():
    return {
        "client_id": "foo",
        "client_secret": "bar",
        "tenant_id": "0B0C960B-FCDF-4D0F-8C45-2E03BB59DDEB",
        "workspace_id": "64ED5CAD-7C10-4684-8180-826122881108",
        "extract_lineage": False,
        "extract_reports": False,
        "extract_ownership": True,
        "convert_lineage_urns_to_lowercase": False,
        "workspace_id_pattern": {"allow": ["64ED5CAD-7C10-4684-8180-826122881108"]},
        "dataset_type_mapping": {
            "PostgreSql": "postgres",
            "Oracle": "oracle",
        },
        "env": "DEV",
        "extract_workspaces_to_containers": False,
        "enable_advance_lineage_sql_construct": False,
    }


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_powerbi_ingest(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:
    enable_logging()

    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(request_mock=requests_mock)

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_mces.json",
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    golden_file = "golden_test_ingest.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=f"{tmp_path}/powerbi_mces.json",
        golden_path=f"{test_resources_dir}/{golden_file}",
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_powerbi_test_connection_success(mock_msal):
    report = test_connection_helpers.run_test_connection(
        PowerBiDashboardSource, default_source_config()
    )
    test_connection_helpers.assert_basic_connectivity_success(report)


@freeze_time(FROZEN_TIME)
@pytest.mark.integration
def test_powerbi_test_connection_failure():
    report = test_connection_helpers.run_test_connection(
        PowerBiDashboardSource, default_source_config()
    )
    test_connection_helpers.assert_basic_connectivity_failure(
        report, "Unable to get authority configuration"
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_powerbi_platform_instance_ingest(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:
    enable_logging()

    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(request_mock=requests_mock)

    output_path: str = f"{tmp_path}/powerbi_platform_instance_mces.json"

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                    "platform_instance": "aws-ap-south-1",
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": output_path,
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    golden_file = "golden_test_platform_instance_ingest.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=output_path,
        golden_path=f"{test_resources_dir}/{golden_file}",
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_powerbi_ingest_urn_lower_case(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:
    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(request_mock=requests_mock)

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                    "convert_urns_to_lowercase": True,
                    "convert_lineage_urns_to_lowercase": True,
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_lower_case_urn_mces.json",
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    golden_file = "golden_test_lower_case_urn_ingest.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=f"{tmp_path}/powerbi_lower_case_urn_mces.json",
        golden_path=f"{test_resources_dir}/{golden_file}",
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_override_ownership(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:
    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(request_mock=requests_mock)

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                    "extract_ownership": False,
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_mces_disabled_ownership.json",
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    mce_out_file = "golden_test_disabled_ownership.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=f"{tmp_path}/powerbi_mces_disabled_ownership.json",
        golden_path=f"{test_resources_dir}/{mce_out_file}",
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_scan_all_workspaces(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:

    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(request_mock=requests_mock)

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                    "extract_reports": False,
                    "extract_ownership": False,
                    "workspace_id_pattern": {
                        "deny": ["64ED5CAD-7322-4684-8180-826122881108"],
                    },
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_mces_scan_all_workspaces.json",
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()

    golden_file = "golden_test_scan_all_workspaces.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=f"{tmp_path}/powerbi_mces_scan_all_workspaces.json",
        golden_path=f"{test_resources_dir}/{golden_file}",
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_extract_reports(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:

    enable_logging()

    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(request_mock=requests_mock)

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                    "extract_reports": True,
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_report_mces.json",
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    golden_file = "golden_test_report.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=f"{tmp_path}/powerbi_report_mces.json",
        golden_path=f"{test_resources_dir}/{golden_file}",
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_extract_lineage(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:
    enable_logging()

    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(request_mock=requests_mock)

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-lineage-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                    "extract_lineage": True,
                    "dataset_type_mapping": {
                        "PostgreSql": {"platform_instance": "operational_instance"},
                        "Oracle": {
                            "platform_instance": "high_performance_production_unit"
                        },
                        "Sql": {"platform_instance": "reporting-db"},
                        "Snowflake": {"platform_instance": "sn-2"},
                    },
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_lineage_mces.json",
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    golden_file = "golden_test_lineage.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=f"{tmp_path}/powerbi_lineage_mces.json",
        golden_path=f"{test_resources_dir}/{golden_file}",
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_extract_endorsements(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:
    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(request_mock=requests_mock)

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                    "extract_reports": False,
                    "extract_endorsements_to_tags": True,
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_endorsement_mces.json",
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    mce_out_file = "golden_test_endorsement.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=f"{tmp_path}/powerbi_endorsement_mces.json",
        golden_path=f"{test_resources_dir}/{mce_out_file}",
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_admin_access_is_not_allowed(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:
    enable_logging()

    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(
        request_mock=requests_mock,
        override_data={
            "https://api.powerbi.com/v1.0/myorg/admin/workspaces/getInfo": {
                "method": "POST",
                "status_code": 403,
                "json": {},
            },
        },
    )

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-admin-api-disabled-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                    "extract_lineage": True,
                    "dataset_type_mapping": {
                        "PostgreSql": {"platform_instance": "operational_instance"},
                        "Oracle": {
                            "platform_instance": "high_performance_production_unit"
                        },
                        "Sql": {"platform_instance": "reporting-db"},
                        "Snowflake": {"platform_instance": "sn-2"},
                    },
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/golden_test_admin_access_not_allowed_mces.json",
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    golden_file = "golden_test_admin_access_not_allowed.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=f"{tmp_path}/golden_test_admin_access_not_allowed_mces.json",
        golden_path=f"{test_resources_dir}/{golden_file}",
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
def test_workspace_container(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:
    enable_logging()

    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(request_mock=requests_mock)

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                    "workspace_id_pattern": {
                        "deny": ["64ED5CAD-7322-4684-8180-826122881108"],
                    },
                    "extract_workspaces_to_containers": True,
                    "extract_datasets_to_containers": True,
                    "extract_reports": True,
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_container_mces.json",
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    mce_out_file = "golden_test_container.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=f"{tmp_path}/powerbi_container_mces.json",
        golden_path=f"{test_resources_dir}/{mce_out_file}",
    )


@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
def test_access_token_expiry_with_long_expiry(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:
    enable_logging()

    register_mock_api(request_mock=requests_mock)

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_access_token_mces.json",
                },
            },
        }
    )

    # for long expiry, the token should only be requested once.
    MsalClient.token = {
        "access_token": "dummy2",
        "expires_in": 3600,
    }

    MsalClient.reset()
    pipeline.run()
    # We expect the token to be requested twice (once for AdminApiResolver and one for RegularApiResolver)
    assert MsalClient.call_num == 2


@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
def test_access_token_expiry_with_short_expiry(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:
    enable_logging()

    register_mock_api(request_mock=requests_mock)

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_access_token_mces.json",
                },
            },
        }
    )

    # for short expiry, the token should be requested when expires.
    MsalClient.token = {
        "access_token": "dummy",
        "expires_in": 0,
    }
    pipeline.run()
    assert MsalClient.call_num > 2


def dataset_type_mapping_set_to_all_platform(pipeline: Pipeline) -> None:
    source_config: PowerBiDashboardSourceConfig = cast(
        PowerBiDashboardSource, pipeline.source
    ).source_config

    assert source_config.dataset_type_mapping is not None

    # Generate default dataset_type_mapping and compare it with source_config.dataset_type_mapping
    default_dataset_type_mapping: dict = {}
    for item in SupportedDataPlatform:
        default_dataset_type_mapping[
            item.value.powerbi_data_platform_name
        ] = item.value.datahub_data_platform_name

    assert default_dataset_type_mapping == source_config.dataset_type_mapping


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_dataset_type_mapping_should_set_to_all(
    mock_msal, pytestconfig, tmp_path, mock_time, requests_mock
):
    """
    Here we don't need to run the pipeline. We need to verify dataset_type_mapping is set to default dataplatform
    """
    register_mock_api(request_mock=requests_mock)

    new_config: dict = {**default_source_config()}

    del new_config["dataset_type_mapping"]

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **new_config,
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_lower_case_urn_mces.json",
                },
            },
        }
    )

    dataset_type_mapping_set_to_all_platform(pipeline)


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_dataset_type_mapping_error(
    mock_msal, pytestconfig, tmp_path, mock_time, requests_mock
):
    """
    Here we don't need to run the pipeline. We need to verify if both dataset_type_mapping and server_to_platform_instance
    are set then value error should get raised
    """
    register_mock_api(request_mock=requests_mock)

    with pytest.raises(Exception, match=r"dataset_type_mapping is deprecated"):
        Pipeline.create(
            {
                "run_id": "powerbi-test",
                "source": {
                    "type": "powerbi",
                    "config": {
                        **default_source_config(),
                        "server_to_platform_instance": {
                            "localhost": {
                                "platform_instance": "test",
                            }
                        },
                    },
                },
                "sink": {
                    "type": "file",
                    "config": {
                        "filename": f"{tmp_path}/powerbi_lower_case_urn_mces.json",
                    },
                },
            }
        )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
def test_server_to_platform_map(
    mock_msal, pytestconfig, tmp_path, mock_time, requests_mock
):
    enable_logging()

    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"
    new_config: dict = {
        **default_source_config(),
        "extract_lineage": True,
        "convert_lineage_urns_to_lowercase": True,
    }

    del new_config["dataset_type_mapping"]

    new_config["server_to_platform_instance"] = {
        "hp123rt5.ap-southeast-2.fakecomputing.com": {
            "platform_instance": "snowflake_production_instance",
            "env": "PROD",
        },
        "my-test-project": {
            "platform_instance": "bigquery-computing-dev-account",
            "env": "QA",
        },
        "localhost:1521": {"platform_instance": "oracle-sales-instance", "env": "PROD"},
    }

    register_mock_api(request_mock=requests_mock)

    output_path: str = f"{tmp_path}/powerbi_server_to_platform_instance_mces.json"

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": new_config,
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": output_path,
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    golden_file_path: str = (
        f"{test_resources_dir}/golden_test_server_to_platform_instance.json"
    )

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=output_path,
        golden_path=golden_file_path,
    )
    # As server_to_platform_instance map is provided, the old dataset_type_mapping
    # should be set to all supported platform
    # to process all available upstream lineage even if mapping for platform instance is
    # not provided in server_to_platform_instance map
    dataset_type_mapping_set_to_all_platform(pipeline)


def validate_pipeline(pipeline: Pipeline) -> None:
    mock_workspace: Workspace = Workspace(
        id="64ED5CAD-7C10-4684-8180-826122881108",
        name="demo-workspace",
        datasets={},
        dashboards=[],
        reports=[],
        report_endorsements={},
        dashboard_endorsements={},
        scan_result={},
        independent_datasets=[],
    )
    # Fetch actual reports
    reports: List[Report] = cast(
        PowerBiDashboardSource, pipeline.source
    ).powerbi_client.get_reports(workspace=mock_workspace)

    assert len(reports) == 2
    # Generate expected reports using mock reports
    mock_reports: List[Dict] = [
        {
            "datasetId": "05169CD2-E713-41E6-9600-1D8066D95445",
            "id": "5b218778-e7a5-4d73-8187-f10824047715",
            "name": "SalesMarketing",
            "description": "Acryl sales marketing report",
            "pages": [
                {
                    "name": "ReportSection",
                    "displayName": "Regional Sales Analysis",
                    "order": "0",
                },
                {
                    "name": "ReportSection1",
                    "displayName": "Geographic Analysis",
                    "order": "1",
                },
            ],
        },
        {
            "datasetId": "05169CD2-E713-41E6-9600-1D8066D95445",
            "id": "e9fd6b0b-d8c8-4265-8c44-67e183aebf97",
            "name": "Product",
            "description": "Acryl product report",
            "pages": [],
        },
    ]
    expected_reports: List[Report] = [
        Report(
            id=report[Constant.ID],
            name=report[Constant.NAME],
            webUrl="",
            embedUrl="",
            description=report[Constant.DESCRIPTION],
            pages=[
                Page(
                    id="{}.{}".format(
                        report[Constant.ID], page[Constant.NAME].replace(" ", "_")
                    ),
                    name=page[Constant.NAME],
                    displayName=page[Constant.DISPLAY_NAME],
                    order=page[Constant.ORDER],
                )
                for page in report["pages"]
            ],
            users=[],
            tags=[],
            dataset=mock_workspace.datasets.get(report[Constant.DATASET_ID]),
        )
        for report in mock_reports
    ]
    # Compare actual and expected reports
    for i in range(2):
        assert reports[i].id == expected_reports[i].id
        assert reports[i].name == expected_reports[i].name
        assert reports[i].description == expected_reports[i].description
        assert reports[i].dataset == expected_reports[i].dataset
        assert reports[i].pages == expected_reports[i].pages


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
@pytest.mark.integration
def test_reports_with_failed_page_request(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:
    """
    Test that all reports are fetched even if a single page request fails
    """
    register_mock_api(
        request_mock=requests_mock,
        override_data={
            "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/reports": {
                "method": "GET",
                "status_code": 200,
                "json": {
                    "value": [
                        {
                            "datasetId": "05169CD2-E713-41E6-9600-1D8066D95445",
                            "id": "5b218778-e7a5-4d73-8187-f10824047715",
                            "name": "SalesMarketing",
                            "description": "Acryl sales marketing report",
                            "webUrl": "https://app.powerbi.com/groups/64ED5CAD-7C10-4684-8180-826122881108/reports/5b218778-e7a5-4d73-8187-f10824047715",
                            "embedUrl": "https://app.powerbi.com/reportEmbed?reportId=5b218778-e7a5-4d73-8187-f10824047715&groupId=64ED5CAD-7C10-4684-8180-826122881108",
                        },
                        {
                            "datasetId": "05169CD2-E713-41E6-9600-1D8066D95445",
                            "id": "e9fd6b0b-d8c8-4265-8c44-67e183aebf97",
                            "name": "Product",
                            "description": "Acryl product report",
                            "webUrl": "https://app.powerbi.com/groups/64ED5CAD-7C10-4684-8180-826122881108/reports/e9fd6b0b-d8c8-4265-8c44-67e183aebf97",
                            "embedUrl": "https://app.powerbi.com/reportEmbed?reportId=e9fd6b0b-d8c8-4265-8c44-67e183aebf97&groupId=64ED5CAD-7C10-4684-8180-826122881108",
                        },
                    ]
                },
            },
            "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/reports/5b218778-e7a5-4d73-8187-f10824047715": {
                "method": "GET",
                "status_code": 200,
                "json": {
                    "datasetId": "05169CD2-E713-41E6-9600-1D8066D95445",
                    "id": "5b218778-e7a5-4d73-8187-f10824047715",
                    "name": "SalesMarketing",
                    "description": "Acryl sales marketing report",
                    "webUrl": "https://app.powerbi.com/groups/64ED5CAD-7C10-4684-8180-826122881108/reports/5b218778-e7a5-4d73-8187-f10824047715",
                    "embedUrl": "https://app.powerbi.com/reportEmbed?reportId=5b218778-e7a5-4d73-8187-f10824047715&groupId=64ED5CAD-7C10-4684-8180-826122881108",
                },
            },
            "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/reports/e9fd6b0b-d8c8-4265-8c44-67e183aebf97": {
                "method": "GET",
                "status_code": 200,
                "json": {
                    "datasetId": "05169CD2-E713-41E6-9600-1D8066D95445",
                    "id": "e9fd6b0b-d8c8-4265-8c44-67e183aebf97",
                    "name": "Product",
                    "description": "Acryl product report",
                    "webUrl": "https://app.powerbi.com/groups/64ED5CAD-7C10-4684-8180-826122881108/reports/e9fd6b0b-d8c8-4265-8c44-67e183aebf97",
                    "embedUrl": "https://app.powerbi.com/reportEmbed?reportId=e9fd6b0b-d8c8-4265-8c44-67e183aebf97&groupId=64ED5CAD-7C10-4684-8180-826122881108",
                },
            },
            "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/reports/5b218778-e7a5-4d73-8187-f10824047715/pages": {
                "method": "GET",
                "status_code": 200,
                "json": {
                    "value": [
                        {
                            "displayName": "Regional Sales Analysis",
                            "name": "ReportSection",
                            "order": "0",
                        },
                        {
                            "displayName": "Geographic Analysis",
                            "name": "ReportSection1",
                            "order": "1",
                        },
                    ]
                },
            },
            "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/reports/e9fd6b0b-d8c8-4265-8c44-67e183aebf97/pages": {
                "method": "GET",
                "status_code": 400,
                "json": {
                    "error": {
                        "code": "InvalidRequest",
                        "message": "Request is currently not supported for RDL reports",
                    }
                },
            },
        },
    )

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                    "extract_reports": True,
                    "platform_instance": "aws-ap-south-1",
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}powerbi_reports_with_failed_page_request_mces.json",
                },
            },
        }
    )

    validate_pipeline(pipeline)


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
def test_independent_datasets_extraction(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:

    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(
        request_mock=requests_mock,
        override_data={
            "https://api.powerbi.com/v1.0/myorg/groups": {
                "method": "GET",
                "status_code": 200,
                "json": {
                    "@odata.count": 3,
                    "value": [
                        {
                            "id": "64ED5CAD-7C10-4684-8180-826122881108",
                            "isReadOnly": True,
                            "name": "demo-workspace",
                            "type": "Workspace",
                        },
                    ],
                },
            },
            "https://api.powerbi.com/v1.0/myorg/admin/workspaces/scanResult/4674efd1-603c-4129-8d82-03cf2be05aff": {
                "method": "GET",
                "status_code": 200,
                "json": {
                    "workspaces": [
                        {
                            "id": "64ED5CAD-7C10-4684-8180-826122881108",
                            "name": "demo-workspace",
                            "state": "Active",
                            "datasets": [
                                {
                                    "id": "91580e0e-1680-4b1c-bbf9-4f6764d7a5ff",
                                    "tables": [
                                        {
                                            "name": "employee_ctc",
                                            "source": [
                                                {
                                                    "expression": "dummy",
                                                }
                                            ],
                                        }
                                    ],
                                },
                            ],
                        },
                    ]
                },
            },
            "https://api.powerbi.com/v1.0/myorg/groups/64ED5CAD-7C10-4684-8180-826122881108/dashboards": {
                "method": "GET",
                "status_code": 200,
                "json": {"value": []},
            },
        },
    )

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_source_config(),
                    "extract_independent_datasets": True,
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_independent_mces.json",
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    golden_file = "golden_test_independent_datasets.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=f"{tmp_path}/powerbi_independent_mces.json",
        golden_path=f"{test_resources_dir}/{golden_file}",
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
def test_cll_extraction(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:

    test_resources_dir = pytestconfig.rootpath / "tests/integration/powerbi"

    register_mock_api(
        request_mock=requests_mock,
    )

    default_conf: dict = default_source_config()

    del default_conf[
        "dataset_type_mapping"
    ]  # delete this key so that connector set it to default (all dataplatform)

    pipeline = Pipeline.create(
        {
            "run_id": "powerbi-test",
            "source": {
                "type": "powerbi",
                "config": {
                    **default_conf,
                    "extract_lineage": True,
                    "extract_column_level_lineage": True,
                    "enable_advance_lineage_sql_construct": True,
                    "native_query_parsing": True,
                    "extract_independent_datasets": True,
                },
            },
            "sink": {
                "type": "file",
                "config": {
                    "filename": f"{tmp_path}/powerbi_cll_mces.json",
                },
            },
        }
    )

    pipeline.run()
    pipeline.raise_from_status()
    golden_file = "golden_test_cll.json"

    mce_helpers.check_golden_file(
        pytestconfig,
        output_path=f"{tmp_path}/powerbi_cll_mces.json",
        golden_path=f"{test_resources_dir}/{golden_file}",
    )


@freeze_time(FROZEN_TIME)
@mock.patch("msal.ConfidentialClientApplication", side_effect=mock_msal_cca)
def test_cll_extraction_flags(
    mock_msal: MagicMock,
    pytestconfig: pytest.Config,
    tmp_path: str,
    mock_time: datetime.datetime,
    requests_mock: Any,
) -> None:

    register_mock_api(
        request_mock=requests_mock,
    )

    default_conf: dict = default_source_config()
    pattern: str = re.escape(
        "Enable all these flags in recipe: ['native_query_parsing', 'enable_advance_lineage_sql_construct', 'extract_lineage']"
    )

    with pytest.raises(Exception, match=pattern):

        Pipeline.create(
            {
                "run_id": "powerbi-test",
                "source": {
                    "type": "powerbi",
                    "config": {
                        **default_conf,
                        "extract_column_level_lineage": True,
                    },
                },
                "sink": {
                    "type": "file",
                    "config": {
                        "filename": f"{tmp_path}/powerbi_cll_mces.json",
                    },
                },
            }
        )
