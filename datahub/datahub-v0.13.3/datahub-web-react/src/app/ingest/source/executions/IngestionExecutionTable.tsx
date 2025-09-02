import React from 'react';
import { Empty, Pagination, Typography } from 'antd';
import styled from 'styled-components';
import { StyledTable } from '../../../entity/shared/components/styled/StyledTable';
import { ExecutionRequest } from '../../../../types.generated';
import { ButtonsColumn, SourceColumn, StatusColumn, TimeColumn } from './IngestionExecutionTableColumns';
import { SUCCESS } from '../utils';
import { formatDuration } from '../../../shared/formatDuration';
import { SearchCfg } from '../../../../conf';

const PaginationInfoContainer = styled.span`
    padding: 8px;
    padding-left: 16px;
    border-top: 1px solid;
    border-color: ${(props) => props.theme.styles['border-color-base']};
    display: flex;
    justify-content: space-between;
    align-items: center;
`;

const StyledPagination = styled(Pagination)`
    margin: 0px;
    padding: 0px;
`;

const PaginationInfo = styled(Typography.Text)`
    padding: 0px;
`;

interface Props {
    executionRequests: ExecutionRequest[];
    setFocusExecutionUrn: (urn: string) => void;
    handleViewDetails: (urn: string) => void;
    handleCancelExecution: (urn: string) => void;
    handleRollbackExecution: (runId: string) => void;
    onChangePage: (number: any) => void;
    setNumResultsPerPage: (number: any) => void;
    totalExecution?: number | null;
    page?: any;
    pageSize?: any;
    lastResultIndex?: any;
}

export default function IngestionExecutionTable({
    executionRequests,
    onChangePage,
    setFocusExecutionUrn,
    handleViewDetails,
    handleCancelExecution,
    handleRollbackExecution,
    setNumResultsPerPage,
    totalExecution,
    pageSize,
    lastResultIndex,
    page,
}: Props) {
    const tableColumns = [
        {
            title: 'Requested At',
            dataIndex: 'requestedAt',
            key: 'requestedAt',
            render: TimeColumn,
        },
        {
            title: 'Started At',
            dataIndex: 'executedAt',
            key: 'executedAt',
            render: TimeColumn,
        },
        {
            title: 'Duration',
            dataIndex: 'duration',
            key: 'duration',
            render: (durationMs: number) => formatDuration(durationMs),
        },
        {
            title: 'Status',
            dataIndex: 'status',
            key: 'status',
            render: (status: any, record) => (
                <StatusColumn status={status} record={record} setFocusExecutionUrn={setFocusExecutionUrn} />
            ),
        },
        {
            title: 'Source',
            dataIndex: 'source',
            key: 'source',
            render: SourceColumn,
        },
        {
            title: '',
            dataIndex: '',
            key: 'x',
            render: (_, record: any) => (
                <ButtonsColumn
                    record={record}
                    handleViewDetails={handleViewDetails}
                    handleCancelExecution={handleCancelExecution}
                    handleRollbackExecution={handleRollbackExecution}
                />
            ),
        },
    ];

    const mostRecentSuccessfulExecution =
        page === 1 && executionRequests.find((execution) => execution.result?.status === SUCCESS);

    const tableData = executionRequests.map((execution) => ({
        urn: execution.urn,
        id: execution.id,
        source: execution.input.source.type,
        requestedAt: execution.input?.requestedAt,
        executedAt: execution.result?.startTimeMs,
        duration: execution.result?.durationMs,
        status: execution.result?.status,
        showRollback: mostRecentSuccessfulExecution && execution?.urn === mostRecentSuccessfulExecution?.urn,
    }));

    return (
        <>
            <StyledTable
                columns={tableColumns}
                dataSource={tableData}
                rowKey="id"
                locale={{
                    emptyText: <Empty description="No Executions found!" image={Empty.PRESENTED_IMAGE_SIMPLE} />,
                }}
                pagination={false}
            />
            <PaginationInfoContainer>
                <PaginationInfo>
                    <b>
                        {lastResultIndex > 0 ? (page - 1) * pageSize + 1 : 0} - {lastResultIndex}
                    </b>{' '}
                    of <b>{totalExecution}</b>
                </PaginationInfo>
                <StyledPagination
                    current={page}
                    pageSize={pageSize}
                    total={totalExecution as any}
                    showLessItems
                    onChange={onChangePage}
                    showSizeChanger={(totalExecution as any) > SearchCfg.RESULTS_PER_PAGE}
                    onShowSizeChange={(_currNum, newNum) => setNumResultsPerPage(newNum)}
                    pageSizeOptions={['10', '20', '50', '100']}
                />
            </PaginationInfoContainer>
        </>
    );
}
