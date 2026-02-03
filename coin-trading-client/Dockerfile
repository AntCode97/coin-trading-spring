# React Frontend Dockerfile
# Multi-stage build: Build + nginx 서빙

# ===========================================
# Stage 1: Build React
# ===========================================
FROM node:20.18.0-alpine AS builder

WORKDIR /app

# Copy package files
COPY package*.json ./

# Install dependencies
RUN npm ci

# Copy source and build
COPY . .

# Build React app
RUN npm run build

# ===========================================
# Stage 2: nginx 서빙
# ===========================================
FROM nginx:alpine

# Copy nginx config
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy React build output
COPY --from=builder /app/dist /usr/share/nginx/html

# Expose port 80
EXPOSE 80

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD wget -q --spider http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
