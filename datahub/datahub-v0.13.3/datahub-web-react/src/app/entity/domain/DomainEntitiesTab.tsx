import React from 'react';
import { useEntityData } from '../shared/EntityContext';
import { EntityType } from '../../../types.generated';
import { EmbeddedListSearchSection } from '../shared/components/styled/search/EmbeddedListSearchSection';
import { UnionType } from '../../search/utils/constants';

export const DomainEntitiesTab = () => {
    const { urn, entityType } = useEntityData();

    let fixedFilter;
    // Set a fixed filter corresponding to the current entity urn.
    if (entityType === EntityType.Domain) {
        fixedFilter = {
            field: 'domains',
            values: [urn],
        };
    }

    return (
        <EmbeddedListSearchSection
            fixedFilters={{
                unionType: UnionType.AND,
                filters: [fixedFilter],
            }}
            emptySearchQuery="*"
            placeholderText="Filter domain entities..."
            skipCache
            applyView
        />
    );
};
