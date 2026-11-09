import { createHash } from 'node:crypto';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, PutCommand, DeleteCommand, QueryCommand } from '@aws-sdk/lib-dynamodb';

// The identity is taken only from a verified Cognito access token, never from a client-supplied user ID.
export function createIdentityVerifier({ region, userPoolId, clientId, keySet }) {
  const issuer = `https://cognito-idp.${region}.amazonaws.com/${userPoolId}`;
  const jwks = keySet || createRemoteJWKSet(new URL(`${issuer}/.well-known/jwks.json`));
  return async token => {
    if (!token || token.length > 8192) throw new Error('로그인이 필요해요');
    try {
      const { payload } = await jwtVerify(token, jwks, { issuer, algorithms:['RS256'] });
      if (payload.token_use !== 'access' || payload.client_id !== clientId || typeof payload.sub !== 'string' || !payload.sub) throw new Error('invalid claims');
      return payload.sub;
    } catch { throw new Error('로그인을 다시 해 주세요'); }
  };
}

const clean = (v,n) => typeof v === 'string' ? [...v.trim()].slice(0,n).join('') : '';
export function validateSavedPlace(input) {
  const name=clean(input?.name,100), address=clean(input?.address,200), latitude=input?.latitude, longitude=input?.longitude;
  if (!name || !Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude)>90 || Math.abs(longitude)>180) throw new Error('장소 정보를 확인해 주세요');
  const id=createHash('sha256').update(JSON.stringify([name,latitude,longitude])).digest('hex');
  return {id,name,address,latitude,longitude};
}

export function createSavedPlaceStore({ region, tableName, client }) {
  const db=client || DynamoDBDocumentClient.from(new DynamoDBClient({region}));
  return {
    async list(userId) {
      const places=[]; let start;
      do {
        const page=await db.send(new QueryCommand({TableName:tableName,KeyConditionExpression:'pk = :pk AND begins_with(sk, :prefix)',ExpressionAttributeValues:{':pk':`USER#${userId}`,':prefix':'PLACE#'},ExclusiveStartKey:start,Limit:100}));
        for (const row of page.Items || []) if (row.place) places.push(row.place);
        start=page.LastEvaluatedKey;
      } while(start && places.length < 500);
      return places;
    },
    async save(userId, input) {
      const place=validateSavedPlace(input);
      await db.send(new PutCommand({TableName:tableName,Item:{pk:`USER#${userId}`,sk:`PLACE#${place.id}`,place}}));
      return place;
    },
    async remove(userId,id) {
      if (!/^[a-f0-9]{64}$/.test(id)) throw new Error('장소를 확인해 주세요');
      await db.send(new DeleteCommand({TableName:tableName,Key:{pk:`USER#${userId}`,sk:`PLACE#${id}`}}));
    },
  };
}
