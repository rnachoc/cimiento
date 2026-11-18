FROM node:24.22.3-alpine AS build
WORKDIR /w
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY angular.json tsconfig*.json ./
COPY public public
COPY src src
RUN npx ng build

FROM nginx:1.29-alpine
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /w/dist/console/browser /usr/share/nginx/html
EXPOSE 8080
