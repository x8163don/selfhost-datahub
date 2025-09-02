import merge from 'deepmerge';
import { unionBy, keyBy, values } from 'lodash';
import { useLocation } from 'react-router-dom';
import * as QueryString from 'query-string';
import { Dataset, Entity, Maybe, SiblingProperties } from '../../../types.generated';
import { GenericEntityProperties } from './types';

export function stripSiblingsFromEntity(entity: any) {
    return {
        ...entity,
        siblings: null,
        siblingPlatforms: null,
    };
}
function cleanHelper(obj, visited) {
    if (visited.has(obj)) return obj;
    visited.add(obj);

    const object = obj;
    Object.entries(object).forEach(([k, v]) => {
        if (v && typeof v === 'object') {
            cleanHelper(v, visited);
        }
        if ((v && typeof v === 'object' && !Object.keys(v).length) || v === null || v === undefined || v === '') {
            if (Array.isArray(object)) {
                object.splice(Number(k), 1);
            } else if (Object.getOwnPropertyDescriptor(object, k)?.configurable) {
                // TODO(hsheth2): Not sure why we needed to add the above "configurable" check.
                // However, I was getting errors when it was not present in dev mode (but not in prod mode).
                delete object[k];
            }
        }
    });
    return object;
}

function clean(obj) {
    const visited = new Set();
    return cleanHelper(obj, visited);
}

const combineMerge = (target, source, options) => {
    const destination = target.slice();

    source.forEach((item, index) => {
        if (typeof destination[index] === 'undefined') {
            destination[index] = options.cloneUnlessOtherwiseSpecified(item, options);
        } else if (options.isMergeableObject(item)) {
            destination[index] = merge(target[index], item, options);
        } else if (target.indexOf(item) === -1) {
            destination.push(item);
        }
    });
    return destination;
};

function convertObjectKeysToLowercase(object: Record<string, unknown>) {
    return Object.fromEntries(Object.entries(object).map(([key, value]) => [key.toLowerCase(), value]));
}

// use when you want to merge an array of objects by key in the object as opposed to by index of array
const mergeArrayOfObjectsByKey = (destinationArray: any[], sourceArray: any[], key: string) => {
    const destination = convertObjectKeysToLowercase(keyBy(destinationArray, key));
    const source = convertObjectKeysToLowercase(keyBy(sourceArray, key));

    return values(merge(destination, source));
};

const mergeTags = (destinationArray, sourceArray, _options) => {
    return unionBy(destinationArray, sourceArray, 'tag.urn');
};

const mergeTerms = (destinationArray, sourceArray, _options) => {
    return unionBy(destinationArray, sourceArray, 'term.urn');
};

const mergeAssertions = (destinationArray, sourceArray, _options) => {
    return unionBy(destinationArray, sourceArray, 'urn');
};

const mergeProperties = (destinationArray, sourceArray, _options) => {
    return unionBy(destinationArray, sourceArray, 'key');
};

const mergeOwners = (destinationArray, sourceArray, _options) => {
    return unionBy(destinationArray, sourceArray, 'owner.urn');
};

const mergeFields = (destinationArray, sourceArray, _options) => {
    return mergeArrayOfObjectsByKey(destinationArray, sourceArray, 'fieldPath');
};

function getArrayMergeFunction(key) {
    switch (key) {
        case 'tags':
            return mergeTags;
        case 'terms':
            return mergeTerms;
        case 'assertions':
            return mergeAssertions;
        case 'customProperties':
            return mergeProperties;
        case 'owners':
            return mergeOwners;
        case 'fields':
            return mergeFields;
        case 'editableSchemaFieldInfo':
            return mergeFields;
        default:
            return undefined;
    }
}

const customMerge = (isPrimary, key) => {
    if (key === 'upstream' || key === 'downstream') {
        return (_secondary, primary) => primary;
    }
    // take the platform & siblings of whichever entity we're merging with, rather than the primary
    if (key === 'platform' || key === 'siblings') {
        return (secondary, primary) => (isPrimary ? primary : secondary);
    }
    if (key === 'forms') {
        return (_secondary, primary) => primary;
    }
    if (
        key === 'tags' ||
        key === 'terms' ||
        key === 'assertions' ||
        key === 'customProperties' ||
        key === 'owners' ||
        key === 'fields' ||
        key === 'editableSchemaFieldInfo'
    ) {
        return (secondary, primary) => {
            return merge(secondary, primary, {
                arrayMerge: getArrayMergeFunction(key),
                customMerge: customMerge.bind({}, isPrimary),
            });
        };
    }
    return (secondary, primary) => {
        return merge(secondary, primary, {
            arrayMerge: combineMerge,
            customMerge: customMerge.bind({}, isPrimary),
        });
    };
};

export const getEntitySiblingData = <T>(baseEntity: T): Maybe<SiblingProperties> => {
    if (!baseEntity) {
        return null;
    }
    const baseEntityKey = Object.keys(baseEntity)[0];
    const extractedBaseEntity = baseEntity[baseEntityKey];

    // eslint-disable-next-line @typescript-eslint/dot-notation
    return extractedBaseEntity?.['siblings'];
};

// should the entity's metadata win out against its siblings?
export const shouldEntityBeTreatedAsPrimary = (extractedBaseEntity: { siblings?: SiblingProperties | null }) => {
    const siblingAspect = extractedBaseEntity?.siblings;

    const siblingsList = siblingAspect?.siblings || [];

    // if the entity is marked as primary, take its metadata first
    const isPrimarySibling = !!siblingAspect?.isPrimary;

    // if no entity in the cohort is primary, just have the entity whos urn is navigated
    // to be primary
    const hasAnyPrimarySibling = siblingsList.find((sibling) => !!(sibling as any)?.siblings?.isPrimary) !== undefined;

    const isPrimary = isPrimarySibling || !hasAnyPrimarySibling;

    return isPrimary;
};

const combineEntityWithSiblings = (entity: GenericEntityProperties) => {
    // eslint-disable-next-line @typescript-eslint/dot-notation
    const siblingAspect = entity.siblings;
    if ((siblingAspect?.siblings || []).length === 0) {
        return entity;
    }

    // eslint-disable-next-line @typescript-eslint/dot-notation
    const siblings = siblingAspect?.siblings || [];

    const isPrimary = shouldEntityBeTreatedAsPrimary(entity);

    const combinedBaseEntity: any = siblings.reduce(
        (prev, current) =>
            merge(clean(isPrimary ? current : prev), clean(isPrimary ? prev : current), {
                arrayMerge: combineMerge,
                customMerge: customMerge.bind({}, isPrimary),
            }),
        entity,
    );

    // Force the urn of the combined entity to the current entity urn.
    combinedBaseEntity.urn = entity.urn;

    return combinedBaseEntity;
};

export const combineEntityDataWithSiblings = <T>(baseEntity: T): T => {
    if (!baseEntity) {
        return baseEntity;
    }
    const baseEntityKey = Object.keys(baseEntity)[0];
    const extractedBaseEntity = baseEntity[baseEntityKey];

    // eslint-disable-next-line @typescript-eslint/dot-notation
    const siblingAspect = extractedBaseEntity.siblings;
    if ((siblingAspect?.siblings || []).length === 0) {
        return baseEntity;
    }

    const combinedBaseEntity = combineEntityWithSiblings(extractedBaseEntity);

    return { [baseEntityKey]: combinedBaseEntity } as unknown as T;
};

export type CombinedEntity = {
    entity: Entity;
    matchedEntities?: Array<Entity>;
};

type CombinedEntityResult =
    | {
          skipped: true;
      }
    | {
          skipped: false;
          combinedEntity: CombinedEntity;
      };

export function combineSiblingsForEntity(entity: Entity, visitedSiblingUrns: Set<string>): CombinedEntityResult {
    if (visitedSiblingUrns.has(entity.urn)) return { skipped: true };

    const combinedEntity: CombinedEntity = { entity: combineEntityWithSiblings({ ...entity }) };
    const siblings = (combinedEntity.entity as GenericEntityProperties).siblings?.siblings ?? [];
    const isPrimary = (combinedEntity.entity as GenericEntityProperties).siblings?.isPrimary;
    const siblingUrns = siblings.map((sibling) => sibling?.urn);

    if (siblingUrns.length > 0) {
        combinedEntity.matchedEntities = isPrimary
            ? [stripSiblingsFromEntity(combinedEntity.entity), ...siblings]
            : [...siblings, stripSiblingsFromEntity(combinedEntity.entity)];

        combinedEntity.matchedEntities = combinedEntity.matchedEntities.filter(
            (resultToFilter) => (resultToFilter as Dataset).exists,
        );

        siblingUrns.forEach((urn) => urn && visitedSiblingUrns.add(urn));
    }

    return { combinedEntity, skipped: false };
}

export function createSiblingEntityCombiner() {
    const visitedSiblingUrns: Set<string> = new Set();
    return (entity: Entity) => combineSiblingsForEntity(entity, visitedSiblingUrns);
}

// used to determine whether sibling entities should be shown merged or not
export const SEPARATE_SIBLINGS_URL_PARAM = 'separate_siblings';

// used to determine whether sibling entities should be shown merged or not
export function useIsSeparateSiblingsMode() {
    const location = useLocation();
    const params = QueryString.parse(location.search, { arrayFormat: 'comma' });

    return params[SEPARATE_SIBLINGS_URL_PARAM] === 'true';
}
