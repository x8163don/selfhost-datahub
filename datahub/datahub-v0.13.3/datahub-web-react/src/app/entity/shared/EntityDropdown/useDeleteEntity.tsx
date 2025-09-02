import { useState } from 'react';
import { message, Modal } from 'antd';
import { EntityType } from '../../../../types.generated';
import { useEntityRegistry } from '../../../useEntityRegistry';
import { getDeleteEntityMutation } from '../../../shared/deleteUtils';
import analytics, { EventType } from '../../../analytics';
import { useGlossaryEntityData } from '../GlossaryEntityContext';
import { getParentNodeToUpdate, updateGlossarySidebar } from '../../../glossary/utils';
import { useHandleDeleteDomain } from './useHandleDeleteDomain';
import { removeTermFromGlossaryNode } from '../../../glossary/cacheUtils';

/**
 * Performs the flow for deleting an entity of a given type.
 *
 * @param urn the type of the entity to delete
 * @param type the type of the entity to delete
 * @param name the name of the entity to delete
 */
function useDeleteEntity(
    urn: string,
    type: EntityType,
    entityData: any,
    onDelete?: () => void,
    hideMessage?: boolean,
    skipWait?: boolean,
) {
    const [hasBeenDeleted, setHasBeenDeleted] = useState(false);
    const entityRegistry = useEntityRegistry();
    const { isInGlossaryContext, urnsToUpdate, setUrnsToUpdate } = useGlossaryEntityData();
    const { handleDeleteDomain } = useHandleDeleteDomain({ entityData, urn });

    const maybeDeleteEntity = getDeleteEntityMutation(type)();
    const deleteEntity = (maybeDeleteEntity && maybeDeleteEntity[0]) || undefined;
    const client = maybeDeleteEntity?.[1].client;

    function handleDeleteEntity() {
        deleteEntity?.({
            variables: {
                urn,
            },
        })
            .then(() => {
                analytics.event({
                    type: EventType.DeleteEntityEvent,
                    entityUrn: urn,
                    entityType: type,
                });
                if (!hideMessage && !skipWait) {
                    message.loading({
                        content: 'Deleting...',
                        duration: 2,
                    });
                }

                if (entityData.type === EntityType.Domain) {
                    handleDeleteDomain();
                }

                if (client && entityData.type === EntityType.GlossaryTerm && entityData?.parentNodes?.nodes) {
                    removeTermFromGlossaryNode(client, entityData.parentNodes.nodes[0].urn, urn);
                }

                setTimeout(
                    () => {
                        setHasBeenDeleted(true);
                        onDelete?.();
                        if (isInGlossaryContext) {
                            const parentNodeToUpdate = getParentNodeToUpdate(entityData, type);
                            updateGlossarySidebar([parentNodeToUpdate], urnsToUpdate, setUrnsToUpdate);
                        }
                        if (!hideMessage) {
                            message.success({
                                content: `Deleted ${entityRegistry.getEntityName(type)}!`,
                                duration: 2,
                            });
                        }
                    },
                    skipWait ? 0 : 2000,
                );
            })
            .catch((e) => {
                message.destroy();
                message.error({ content: `Failed to delete: \n ${e.message || ''}`, duration: 3 });
            });
    }

    function onDeleteEntity() {
        Modal.confirm({
            title: `Delete ${
                (entityData && entityRegistry.getDisplayName(type, entityData)) || entityRegistry.getEntityName(type)
            }`,
            content: `Are you sure you want to remove this ${entityRegistry.getEntityName(type)}?`,
            onOk() {
                handleDeleteEntity();
            },
            onCancel() {},
            okText: 'Yes',
            maskClosable: true,
            closable: true,
        });
    }

    return { onDeleteEntity, hasBeenDeleted };
}

export default useDeleteEntity;
