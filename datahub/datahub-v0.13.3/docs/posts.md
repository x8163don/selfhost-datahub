import FeatureAvailability from '@site/src/components/FeatureAvailability';

# Posts

<FeatureAvailability/>
DataHub allows users to make Posts that can be displayed on the app. Currently, Posts are only supported on the Home Page, but may be extended to other surfaces of the app in the future. Posts can be used to accomplish the following:

* Allowing Admins to post announcements on the home page
* Pinning important DataHub assets or pages
* Pinning important external links

##  Posts Setup, Prerequisites, and Permissions

Anyone can view Posts on the home page. To create Posts, a user must either have the **Create Global Announcements** Privilege, or possess the **Admin** DataHub Role.

## Using Posts

To create a post, users must use the [createPost](../graphql/mutations.md#createPost) GraphQL mutation. There is currently no way to create posts using the UI, though this will come in the future.

There is only one type of Post that can be currently made, and that is a **Home Page Announcement**. This may be extended in the future to other surfaces.

DataHub currently supports two types of Post content. Posts can either contain **TEXT** or can be a **LINK**. When creating a post through GraphQL, users will have to supply the post content. 

For **TEXT** posts, the following pieces of information are required in the `content` object (of type [UpdatePostContentInput](../graphql/inputObjects.md#updatepostcontentinput)) of the GraphQL `input` (of type [CreatePostInput](../graphql/inputObjects.md#createpostinput))). **TEXT** posts cannot be clicked.
* `contentType: TEXT`
* `title`
* `description`

The `link` and `media` attributes are currently unused for **TEXT** posts.

For **LINK** posts, the following pieces of information are required in the `content` object (of type [UpdatePostContentInput](../graphql/inputObjects.md#updatepostcontentinput)) of the GraphQL `input` (of type [CreatePostInput](../graphql/inputObjects.md#createpostinput))). **LINK** posts redirect to the provided link when clicked.
* `contentType: LINK`
* `title`
* `link`
* `media`. Currently only the **IMAGE** type is supported, and the URL of the image must be provided

The `description` attribute is currently unused for **LINK** posts.

Here are some examples of Posts displayed on the home page, with one **TEXT** post and two **LINK** posts.

<p align="center">
 <img width="70%"  src="https://raw.githubusercontent.com/datahub-project/static-assets/main/imgs/posts/view-posts.png" />
</p>

### GraphQL

* [createPost](../graphql/mutations.md#createpost)
* [listPosts](../graphql/queries.md#listposts)
* [deletePosts](../graphql/queries.md#listposts)

### Examples

##### Create Post
```graphql
mutation test {
  createPost(
    input: {
      postType: HOME_PAGE_ANNOUNCEMENT, 
      content: {
        contentType: TEXT, 
        title: "Planed Upgrade 2023-03-23 20:05 - 2023-03-23 23:05", 
        description: "datahub upgrade to v0.10.1"
      }
    }
  )
}

```

##### List Post

```graphql
query listPosts($input: ListPostsInput!) {
  listPosts(input: $input) {
    start
    count
    total
    posts {
      urn
      type
      postType
      content {
        contentType
        title
        description
        link
        media {
          type
          location
          __typename
        }
        __typename
      }
      __typename
    }
    __typename
  }
}

```
##### Input for list post
```shell
{
  "input": {
    "start": 0,
    "count": 10
  }
}
```

##### Delete Post

```graphql
mutation deletePosting { 
  deletePost (
    urn: "urn:li:post:61dd86fa-9e76-4924-ad45-3a533671835e"
  )
}
```
