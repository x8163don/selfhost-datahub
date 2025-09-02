import { Button, Checkbox, Form, Input, Switch, Typography } from 'antd';
import React, { useMemo, useState } from 'react';
import { Cron } from 'react-js-cron';
import 'react-js-cron/dist/styles.css';
import styled from 'styled-components';
import cronstrue from 'cronstrue';
import { CheckCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { SourceBuilderState, StepProps } from './types';
import { TimezoneSelect } from './TimezoneSelect';
import { ANTD_GRAY, REDESIGN_COLORS } from '../../../entity/shared/constants';
import { lowerFirstLetter } from '../../../shared/textUtil';
import { IngestionSourceBuilderStep } from './steps';

const Section = styled.div`
    display: flex;
    flex-direction: column;
    padding-bottom: 16px;
    padding-top: 0px;
`;

const SelectTemplateHeader = styled(Typography.Title)`
    && {
        margin-bottom: 8px;
    }
`;

const CronText = styled(Typography.Paragraph)`
    &&& {
        margin-bottom: 0px;
    }
    color: ${ANTD_GRAY[7]};
`;

const AdvancedCheckBox = styled(Typography.Text)`
    margin-right: 10px;
    margin-bottom: 8px;
`;
const CronSuccessCheck = styled(CheckCircleOutlined)`
    color: ${REDESIGN_COLORS.BLUE};
    margin-right: 4px;
`;

const ControlsContainer = styled.div`
    display: flex;
    justify-content: space-between;
    margin-top: 8px;
`;

const StyledFormItem = styled(Form.Item)`
    .cron-builder {
        color: ${ANTD_GRAY[7]};
    }
    .cron-builder-select {
        min-width: 100px;
    }
`;

const WarningContainer = styled.div`
    color: ${ANTD_GRAY[7]};
`;

const StyledWarningOutlined = styled(WarningOutlined)`
    margin-right: 4px;
    margin-top: 12px;
`;

const ItemDescriptionText = styled(Typography.Paragraph)``;

const DAILY_MIDNIGHT_CRON_INTERVAL = '0 0 * * *';

export const CreateScheduleStep = ({ state, updateState, goTo, prev }: StepProps) => {
    const { schedule } = state;
    const interval = schedule?.interval?.replaceAll(', ', ' ') || DAILY_MIDNIGHT_CRON_INTERVAL;
    const timezone = schedule?.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone;
    const [scheduleEnabled, setScheduleEnabled] = useState(!!schedule);
    const [advancedCronCheck, setAdvancedCronCheck] = useState(false);
    const [scheduleCronInterval, setScheduleCronInterval] = useState(interval);
    const [scheduleTimezone, setScheduleTimezone] = useState(timezone);

    const cronAsText = useMemo(() => {
        if (scheduleCronInterval) {
            try {
                return {
                    text: `Runs ${lowerFirstLetter(cronstrue.toString(scheduleCronInterval))}.`,
                    error: false,
                };
            } catch (e) {
                return {
                    text: undefined,
                    error: true,
                };
            }
        }
        return {
            text: undefined,
            error: false,
        };
    }, [scheduleCronInterval]);

    const onClickNext = () => {
        if (scheduleEnabled) {
            const newState: SourceBuilderState = {
                ...state,
                schedule: {
                    timezone: scheduleTimezone,
                    interval: scheduleCronInterval,
                },
            };
            updateState(newState);
        } else {
            const newState: SourceBuilderState = {
                ...state,
                schedule: undefined,
            };
            updateState(newState);
        }

        goTo(IngestionSourceBuilderStep.NAME_SOURCE);
    };

    return (
        <>
            <Section>
                <SelectTemplateHeader level={5}>Configure an Ingestion Schedule</SelectTemplateHeader>
            </Section>
            <Form layout="vertical">
                <Form.Item
                    tooltip="Enable to run ingestion on a schedule. Running ingestion on a schedule helps to keep the information inside of DataHub up to date."
                    label={
                        <Typography.Text strong>
                            Run on a schedule <Typography.Text type="secondary">(Recommended)</Typography.Text>
                        </Typography.Text>
                    }
                >
                    <Switch checked={scheduleEnabled} onChange={(v) => setScheduleEnabled(v)} />
                    {!scheduleEnabled && (
                        <WarningContainer>
                            <StyledWarningOutlined />
                            Running ingestion without a schedule may result in out-of-date information.
                        </WarningContainer>
                    )}
                </Form.Item>
                <StyledFormItem required label={<Typography.Text strong>Schedule</Typography.Text>}>
                    <div style={{ paddingBottom: 10, paddingLeft: 10 }}>
                        <AdvancedCheckBox type="secondary">Advanced</AdvancedCheckBox>
                        <Checkbox
                            checked={advancedCronCheck}
                            onChange={(event) => setAdvancedCronCheck(event.target.checked)}
                        />
                    </div>
                    {advancedCronCheck ? (
                        <Input
                            placeholder={DAILY_MIDNIGHT_CRON_INTERVAL}
                            autoFocus
                            value={scheduleCronInterval}
                            onChange={(e) => setScheduleCronInterval(e.target.value)}
                        />
                    ) : (
                        <Cron
                            value={scheduleCronInterval}
                            setValue={setScheduleCronInterval}
                            clearButton={false}
                            className="cron-builder"
                            leadingZero
                        />
                    )}
                    <CronText>
                        {cronAsText.error && <>Invalid cron schedule. Cron must be of UNIX form:</>}
                        {!cronAsText.text && (
                            <Typography.Paragraph keyboard style={{ marginTop: 4 }}>
                                minute, hour, day, month, day of week
                            </Typography.Paragraph>
                        )}
                        {cronAsText.text && (
                            <>
                                <CronSuccessCheck />
                                {cronAsText.text}
                            </>
                        )}
                    </CronText>
                </StyledFormItem>
                <Form.Item required label={<Typography.Text strong>Timezone</Typography.Text>}>
                    <ItemDescriptionText>Choose a timezone for the schedule.</ItemDescriptionText>
                    <TimezoneSelect value={scheduleTimezone} onChange={setScheduleTimezone} />
                </Form.Item>
            </Form>
            <ControlsContainer>
                <Button onClick={prev}>Previous</Button>
                <div>
                    <Button
                        data-testid="ingestion-schedule-next-button"
                        disabled={!interval || interval.length === 0 || cronAsText.error}
                        onClick={onClickNext}
                    >
                        Next
                    </Button>
                </div>
            </ControlsContainer>
        </>
    );
};
